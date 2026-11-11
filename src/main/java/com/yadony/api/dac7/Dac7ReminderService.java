package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.dac7.events.Dac7TaxInfoRequestedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Demande et rappels du numéro fiscal (lot 3b), seule porte d'écriture du suivi {@code tax_info_requested_at} et
 * {@code tax_reminder_count}. Règles du propriétaire :
 * <ul>
 *   <li>seulement un voyageur {@link Dac7SolicitationStatus#SOLLICITER}, revérifié au moment d'écrire ;</li>
 *   <li>demande initiale, rappel 1 à 14 jours de la demande, rappel 2 à 30 jours et au moins 7 jours après le
 *       rappel 1, JAMAIS de troisième ;</li>
 *   <li>un seul message par voyageur sur 24 heures (planificateur et relance manuelle confondus) ;</li>
 *   <li>une ligne de registre par envoi, dans la même transaction que le compteur ; la notification part après
 *       validation ({@link Dac7TaxInfoRequestedEvent}) et respecte les préférences de l'utilisateur.</li>
 * </ul>
 * Jamais bloquant : rien d'autre ne lit ces champs.
 */
@Service
public class Dac7ReminderService {

    public enum Outcome { SENT, NOT_SOLICITABLE, NOT_DUE, TOO_SOON, EXHAUSTED, NOT_FOUND }

    private static final Duration ONE_PER_DAY = Duration.ofHours(24);
    static final String ACCEPTANCE_NOTE_PREFIX = "ACCEPTANCE:";
    /** Note de la demande du seul pays : marque qu'elle a été faite (une seule fois par voyageur). */
    static final String ACCEPTANCE_COUNTRY_NOTE = ACCEPTANCE_NOTE_PREFIX + "RESIDENCE_COUNTRY";

    private final UserRepository users;
    private final Dac7SolicitationPolicy solicitation;
    private final Dac7DueDiligenceService diligence;
    private final Dac7AdminReadModel reads;
    private final Dac7ReminderProperties properties;
    private final ApplicationEventPublisher events;
    private final Dac7CollectionPolicy collection;
    private final TransactionOperations writes;

    @org.springframework.beans.factory.annotation.Autowired
    public Dac7ReminderService(UserRepository users, Dac7SolicitationPolicy solicitation,
                               Dac7DueDiligenceService diligence, Dac7AdminReadModel reads,
                               Dac7ReminderProperties properties, ApplicationEventPublisher events,
                               Dac7CollectionPolicy collection, PlatformTransactionManager transactionManager) {
        this(users, solicitation, diligence, reads, properties, events, collection,
                new TransactionTemplate(transactionManager));
    }

    /** Tests : transaction fournie (ou {@link TransactionOperations#withoutTransaction()}). */
    Dac7ReminderService(UserRepository users, Dac7SolicitationPolicy solicitation, Dac7DueDiligenceService diligence,
                        Dac7AdminReadModel reads, Dac7ReminderProperties properties,
                        ApplicationEventPublisher events, Dac7CollectionPolicy collection,
                        TransactionOperations writes) {
        this.writes = writes;
        this.users = users;
        this.solicitation = solicitation;
        this.diligence = diligence;
        this.reads = reads;
        this.properties = properties;
        this.events = events;
        this.collection = collection;
    }

    /**
     * Collecte ciblée (spec du 11/11/2026, § 3.2 corrigé par R1) : invite NON bloquante après une acceptation de
     * réservation, une seule fois. Appelée après validation de l'acceptation ({@link Dac7AcceptanceListener}) : rien
     * de l'acceptation n'en dépend.
     * <ul>
     *   <li>pays de résidence inconnu : demande du SEUL pays (note {@value #ACCEPTANCE_COUNTRY_NOTE}), une fois ;
     *       le suivi {@code tax_info_requested_at} n'est pas posé (il servira à la vraie demande si le pays est dans
     *       l'Union) ;</li>
     *   <li>résident de l'Union aux informations incomplètes : première demande ({@code REQUEST}, base des rappels)
     *       si aucune n'a encore été faite, avec les codes des champs manquants en note ;</li>
     *   <li>hors périmètre : rien ; complet : rien à demander.</li>
     * </ul>
     * Dans tous les cas, la preuve Didit est inscrite au registre si des valeurs retenues viennent de la pièce. Même
     * règle d'un message par 24 heures que les rappels.
     *
     * <p>La pièce vérifiée est relue chez le prestataire AVANT la transaction d'écriture, sans tenir de connexion JDBC
     * (YADONY-BACK-STAGING-W).
     */
    public Outcome requestAtAcceptance(UUID userId, Instant now) {
        Optional<UserEntity> found = users.findById(userId);
        if (found.isEmpty() || found.get().getDeletedAt() != null) {
            return Outcome.NOT_FOUND;
        }
        Dac7Collection evaluated = collection.evaluate(found.get());
        return writes.execute(status -> recordAtAcceptance(userId, evaluated, now));
    }

    private Outcome recordAtAcceptance(UUID userId, Dac7Collection evaluated, Instant now) {
        // Ligne verrouillée (UPDATE neutre, sans toucher à la version) : deux acceptations simultanées ne demandent
        // jamais deux fois (la branche « pays inconnu » n'écrit pas dans users, le verrou optimiste ne la protège pas).
        if (users.lockForUpdate(userId) == 0) {
            return Outcome.NOT_FOUND;
        }
        UserEntity user = users.findById(userId).orElse(null);
        if (user == null) {
            return Outcome.NOT_FOUND;
        }
        diligence.recordVerifiedSource(userId, evaluated);
        switch (evaluated.status()) {
            case RESIDENCE_INCONNUE -> {
                if (diligence.alreadyRecorded(userId, Dac7DueDiligenceAction.REQUEST, ACCEPTANCE_COUNTRY_NOTE)) {
                    return Outcome.NOT_DUE;
                }
                if (sentWithin(userId, now, ONE_PER_DAY)) {
                    return Outcome.TOO_SOON;
                }
                diligence.record(userId, Dac7DueDiligenceAction.REQUEST, Dac7DueDiligenceChannel.IN_APP, null,
                        ACCEPTANCE_COUNTRY_NOTE);
                events.publishEvent(new Dac7TaxInfoRequestedEvent(userId, Dac7DueDiligenceAction.REQUEST.name(),
                        Dac7TaxInfoRequestedEvent.SCOPE_RESIDENCE_COUNTRY));
                return Outcome.SENT;
            }
            case INFO_REQUIRED -> {
                // Rien que le voyageur puisse fournir (professionnel : identité d'entreprise seule) : pas d'invite.
                if (!evaluated.actionRequired() || user.getTaxInfoRequestedAt() != null) {
                    return Outcome.NOT_DUE;
                }
                if (sentWithin(userId, now, ONE_PER_DAY)) {
                    return Outcome.TOO_SOON;
                }
                user.setTaxInfoRequestedAt(now);
                users.save(user);
                diligence.record(userId, Dac7DueDiligenceAction.REQUEST, Dac7DueDiligenceChannel.IN_APP, null,
                        ACCEPTANCE_NOTE_PREFIX + String.join(",",
                                evaluated.missing().stream().map(Enum::name).toList()));
                events.publishEvent(new Dac7TaxInfoRequestedEvent(userId, Dac7DueDiligenceAction.REQUEST.name(),
                        Dac7TaxInfoRequestedEvent.SCOPE_TAX_INFO));
                return Outcome.SENT;
            }
            case NE_PAS_SOLLICITER -> {
                return Outcome.NOT_SOLICITABLE;
            }
            default -> {
                return Outcome.NOT_DUE;
            }
        }
    }

    /** Planificateur : envoie la démarche due à {@code now}, s'il y en a une. */
    @Transactional
    public Outcome remindIfDue(UUID userId, Instant now) {
        Optional<UserEntity> found = users.findById(userId);
        if (found.isEmpty()) {
            return Outcome.NOT_FOUND;
        }
        UserEntity user = found.get();
        if (solicitation.statusOf(user) != Dac7SolicitationStatus.SOLLICITER) {
            return Outcome.NOT_SOLICITABLE;
        }
        Dac7DueDiligenceAction next = dueAction(user, now);
        if (next == null) {
            return user.getTaxReminderCount() >= 2 ? Outcome.EXHAUSTED : Outcome.NOT_DUE;
        }
        if (sentWithin(userId, now, ONE_PER_DAY)) {
            return Outcome.TOO_SOON;
        }
        record(user, next, Dac7DueDiligenceChannel.IN_APP, null, now);
        return Outcome.SENT;
    }

    /** Relance manuelle de l'équipe : la démarche suivante, au plus une par {@code manualMinInterval}. */
    @Transactional
    public Outcome remindByAdmin(UUID userId, UUID adminId, Instant now) {
        Optional<UserEntity> found = users.findById(userId);
        if (found.isEmpty()) {
            return Outcome.NOT_FOUND;
        }
        UserEntity user = found.get();
        if (solicitation.statusOf(user) != Dac7SolicitationStatus.SOLLICITER) {
            return Outcome.NOT_SOLICITABLE;
        }
        Dac7DueDiligenceAction next = nextAction(user);
        if (next == null) {
            return Outcome.EXHAUSTED;
        }
        if (sentWithin(userId, now, max(properties.manualMinInterval(), ONE_PER_DAY))) {
            return Outcome.TOO_SOON;
        }
        record(user, next, Dac7DueDiligenceChannel.ADMIN, adminId, now);
        return Outcome.SENT;
    }

    /** Démarche suivante dans l'ordre, sans regarder les délais ; nulle après le rappel 2. */
    static Dac7DueDiligenceAction nextAction(UserEntity user) {
        if (user.getTaxInfoRequestedAt() == null) {
            return Dac7DueDiligenceAction.REQUEST;
        }
        return switch (user.getTaxReminderCount()) {
            case 0 -> Dac7DueDiligenceAction.REMINDER_1;
            case 1 -> Dac7DueDiligenceAction.REMINDER_2;
            default -> null;
        };
    }

    /** Démarche due à {@code now} : demande tout de suite, rappels à 14 et 30 jours de la demande. */
    Dac7DueDiligenceAction dueAction(UserEntity user, Instant now) {
        Dac7DueDiligenceAction next = nextAction(user);
        if (next == null || next == Dac7DueDiligenceAction.REQUEST) {
            return next;
        }
        Duration delay = next == Dac7DueDiligenceAction.REMINDER_1
                ? properties.firstReminderDelay() : properties.secondReminderDelay();
        if (user.getTaxInfoRequestedAt().plus(delay).isAfter(now)) {
            return null;
        }
        // Rappel 2 : jamais moins de minGapBetweenReminders après le message précédent, même si le planificateur a
        // été arrêté et que les deux échéances sont passées.
        if (next == Dac7DueDiligenceAction.REMINDER_2
                && sentWithin(user.getId(), now, properties.minGapBetweenReminders())) {
            return null;
        }
        return next;
    }

    private boolean sentWithin(UUID userId, Instant now, Duration window) {
        return reads.lastOutgoing(userId).map(last -> last.plus(window).isAfter(now)).orElse(false);
    }

    private void record(UserEntity user, Dac7DueDiligenceAction action, Dac7DueDiligenceChannel channel,
                        UUID adminId, Instant now) {
        if (action == Dac7DueDiligenceAction.REQUEST) {
            user.setTaxInfoRequestedAt(now);
        } else {
            user.setTaxReminderCount(user.getTaxReminderCount() + 1);
        }
        users.save(user);
        diligence.record(user.getId(), action, channel, adminId, null);
        events.publishEvent(new Dac7TaxInfoRequestedEvent(user.getId(), action.name()));
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
