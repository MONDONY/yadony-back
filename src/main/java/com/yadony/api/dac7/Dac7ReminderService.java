package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.dac7.events.Dac7TaxInfoRequestedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Demande et rappels du numéro fiscal (lot 3b), seule porte d'écriture du suivi {@code tax_info_requested_at} et
 * {@code tax_reminder_count}. Règles du propriétaire :
 * <ul>
 *   <li>seulement un voyageur {@link Dac7SolicitationStatus#SOLLICITER}, revérifié au moment d'écrire ;</li>
 *   <li>demande initiale, rappel 1 à 14 jours de la demande, rappel 2 à 30 jours, JAMAIS de troisième ;</li>
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

    private final UserRepository users;
    private final Dac7SolicitationPolicy solicitation;
    private final Dac7DueDiligenceService diligence;
    private final Dac7AdminReadModel reads;
    private final Dac7ReminderProperties properties;
    private final ApplicationEventPublisher events;

    public Dac7ReminderService(UserRepository users, Dac7SolicitationPolicy solicitation,
                               Dac7DueDiligenceService diligence, Dac7AdminReadModel reads,
                               Dac7ReminderProperties properties, ApplicationEventPublisher events) {
        this.users = users;
        this.solicitation = solicitation;
        this.diligence = diligence;
        this.reads = reads;
        this.properties = properties;
        this.events = events;
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
        return user.getTaxInfoRequestedAt().plus(delay).isAfter(now) ? null : next;
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
