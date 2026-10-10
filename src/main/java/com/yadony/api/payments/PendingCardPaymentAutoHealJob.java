package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Auto-réparation des paiements carte, toutes les 15 minutes, en deux passes bornées.
 *
 * <p><b>1. Paiements PENDING.</b> Le passage en séquestre dépend du webhook
 * {@code payment_intent.amount_capturable_updated} (et, pour une négociation, du {@code /checkout}
 * synchrone). Un webhook non abonné, perdu ou en retard laissait le paiement PENDING ; une
 * livraison intervenue entre-temps ne versait rien (staging, 04 au 10/10/2026). Le job relit chez
 * Stripe les paiements carte PENDING créés il y a 10 min à 7 jours et applique la resynchronisation
 * du bouton admin ({@link PaymentStripeResyncService#resyncAutomatically}) : {@code requires_capture}
 * → séquestre (et versement immédiat si le colis est déjà livré), {@code canceled} → annulé, refus
 * carte → échec. Audit {@code PAYMENT_AUTO_RESYNC_STRIPE} sans acteur.
 *
 * <p><b>2. Séquestres livrés non versés.</b> Paiements carte ESCROW dont le colis est
 * {@code COMPLETED} depuis plus de 15 min, dernière activité (livraison ou passage en séquestre)
 * de moins de 20 h : le versement de la livraison ou du rattrapage a échoué (capture ou Transfer
 * refusé). Nouvel essai par {@link DeliveredEscrowReleaser#releaseIfDelivered} (mêmes gardes,
 * même claim, même clé {@code transfer-<id>}) ; un échec lève l'alerte unique
 * {@code LATE_RELEASE_FAILED_<paymentId>}. Au-delà de 20 h, plus aucun essai automatique : la clé
 * d'idempotence Stripe ne vaut que 24 h, un Transfer parti chez Stripe mais annulé en base
 * pourrait alors être doublé. Le force-release admin prend le relais.
 *
 * <p><b>Ce qui n'est pas repris</b> : les paiements mobile money (pawaPay) ; les PENDING de plus de
 * 7 jours (autorisation carte expirée) ; les checkouts abandonnés ({@code requires_payment_method},
 * {@code requires_confirmation}, {@code requires_action}) de plus de 24 h, laissés aux traitements
 * existants (expiration de la négociation, annulation) ; les séquestres sous litige, partiellement
 * remboursés ou au versement retenu (leur garde a alerté, un admin tranche) ; un versement bloqué
 * par une garde (voyageur gelé, compte Connect inutilisable) n'est tenté qu'une fois par démarrage.
 *
 * <p>Bornes, pour ne jamais inonder Stripe : au plus {@code batch-size} paiements par passe
 * (20 par défaut), <b>plus récents d'abord</b>, lecture paginée jusqu'à remplir le lot ; un
 * paiement trouvé cohérent est relu de moins en moins souvent (toutes les 30 min la première
 * heure, puis toutes les heures, puis toutes les 6 h) ; Stripe indisponible : la passe PENDING
 * s'arrête. L'agenda de relecture est en mémoire : après un redémarrage, les plus récents passent
 * en premier, puis la pagination atteint les suivants. Aucune exclusion entre instances : les deux
 * traitements sont idempotents (gardes atomiques, clés d'idempotence Stripe).
 */
@Component
public class PendingCardPaymentAutoHealJob {

    private static final Logger log = LoggerFactory.getLogger(PendingCardPaymentAutoHealJob.class);

    /** Statuts Stripe d'un checkout jamais mené à terme par l'expéditeur. */
    static final Set<String> NOT_ATTEMPTED = Set.of("requires_payment_method", "requires_confirmation", "requires_action");
    /** Pages lues au plus par passe (taille de page : 5 × le lot). */
    static final int MAX_PAGES = 20;
    /** Délai laissé au versement normal de la livraison avant de le reprendre. */
    static final Duration RELEASE_GRACE = Duration.ofMinutes(15);
    /** Au-delà, plus d'essai automatique (clé d'idempotence Stripe valable 24 h). */
    static final Duration RELEASE_WINDOW = Duration.ofHours(20);
    static final String SOURCE_AUTO_RELEASE = "auto-heal-release";

    private final PaymentRepository paymentRepository;
    private final PaymentStripeResyncService resyncService;
    private final DeliveredEscrowReleaser releaser;
    private final boolean enabled;
    private final Duration minAge;
    private final Duration maxAge;
    private final Duration abandonedAfter;
    private final int batchSize;
    private final Clock clock;

    /** Prochaine relecture autorisée par paiement (absent = relu au prochain passage). */
    private final Map<UUID, Instant> nextCheck = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> nextRelease = new ConcurrentHashMap<>();

    @Autowired
    public PendingCardPaymentAutoHealJob(PaymentRepository paymentRepository, PaymentStripeResyncService resyncService,
                                         DeliveredEscrowReleaser releaser,
                                         @Value("${yadony.payments.auto-heal.enabled:true}") boolean enabled,
                                         @Value("${yadony.payments.auto-heal.min-age:PT10M}") Duration minAge,
                                         @Value("${yadony.payments.auto-heal.max-age:P7D}") Duration maxAge,
                                         @Value("${yadony.payments.auto-heal.abandoned-after:PT24H}") Duration abandonedAfter,
                                         @Value("${yadony.payments.auto-heal.batch-size:20}") int batchSize) {
        this(paymentRepository, resyncService, releaser, enabled, minAge, maxAge, abandonedAfter, batchSize,
                Clock.systemUTC());
    }

    PendingCardPaymentAutoHealJob(PaymentRepository paymentRepository, PaymentStripeResyncService resyncService,
                                  DeliveredEscrowReleaser releaser, boolean enabled, Duration minAge, Duration maxAge,
                                  Duration abandonedAfter, int batchSize, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.resyncService = resyncService;
        this.releaser = releaser;
        this.enabled = enabled;
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.abandonedAfter = abandonedAfter;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${yadony.payments.auto-heal.interval:PT15M}",
            initialDelayString = "${yadony.payments.auto-heal.initial-delay:PT3M}")
    public void scheduledRun() {
        if (enabled) {
            run();
        }
    }

    /** Un passage complet (PENDING puis séquestres livrés). @return le nombre de paiements traités */
    public int run() {
        return healPending() + releaseDeliveredEscrows();
    }

    /** Passe 1 : paiements carte PENDING relus chez Stripe. @return le nombre relus */
    int healPending() {
        Instant now = clock.instant();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        nextCheck.values().removeIf(at -> at.isBefore(now.minus(maxAge)));
        LocalDateTime olderThan = nowUtc.minus(minAge);
        LocalDateTime newerThan = nowUtc.minus(maxAge);
        List<UUID> due = dueCandidates((page, size) -> paymentRepository.findPendingCardPaymentIds(
                olderThan, newerThan, PageRequest.of(page, size)), id -> isDue(nextCheck, id, now));

        int healed = 0;
        int released = 0;
        int checked = 0;
        for (UUID paymentId : due) {
            checked++;
            try {
                PaymentStripeResyncService.Result result = resyncService.resyncAutomatically(paymentId);
                if (result.changed()) {
                    healed++;
                    nextCheck.remove(paymentId);
                    if (result.released()) {
                        released++;
                    }
                    log.warn("Auto-réparation du paiement {} : {} ({})", paymentId, result.action(), result.message());
                } else {
                    nextCheck.put(paymentId, now.plus(backoff(paymentId, result, now)));
                }
            } catch (YadonyBusinessException e) {
                if (e.getStatus() == HttpStatus.BAD_GATEWAY) {
                    log.warn("Auto-réparation interrompue : Stripe indisponible ({})", e.getMessage());
                    break;
                }
                // Écart que la resynchronisation ne tranche pas (montant, statut non géré) : relu
                // dans 6 h ; le rapprochement de 04:30 UTC alerte l'admin.
                log.warn("Auto-réparation du paiement {} impossible : {}", paymentId, e.getMessage());
                nextCheck.put(paymentId, now.plus(Duration.ofHours(6)));
            } catch (RuntimeException e) {
                log.error("Auto-réparation du paiement {} en échec", paymentId, e);
                nextCheck.put(paymentId, now.plus(Duration.ofHours(1)));
            }
        }
        if (checked > 0) {
            log.info("Auto-réparation des paiements carte PENDING : {} relu(s), {} réparé(s), {} versé(s)",
                    checked, healed, released);
        }
        return checked;
    }

    /** Passe 2 : séquestres carte de colis livrés, jamais versés. @return le nombre tentés */
    int releaseDeliveredEscrows() {
        Instant now = clock.instant();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        nextRelease.values().removeIf(at -> at.isBefore(now.minus(RELEASE_WINDOW)));
        LocalDateTime graceBefore = nowUtc.minus(RELEASE_GRACE);
        LocalDateTime windowStart = nowUtc.minus(RELEASE_WINDOW);
        List<UUID> due = dueCandidates((page, size) -> paymentRepository.findDeliveredUnreleasedEscrowIds(
                graceBefore, windowStart, PageRequest.of(page, size)), id -> isDue(nextRelease, id, now));

        int released = 0;
        for (UUID paymentId : due) {
            DeliveredEscrowReleaser.LateRelease result;
            try {
                result = releaser.releaseIfDelivered(paymentId, SOURCE_AUTO_RELEASE);
            } catch (RuntimeException e) {
                log.error("Versement automatique du séquestre {} impossible", paymentId, e);
                nextRelease.put(paymentId, now.plus(Duration.ofHours(1)));
                continue;
            }
            EscrowReleaseOutcome outcome = result.outcome();
            if (outcome.released()) {
                released++;
                nextRelease.remove(paymentId);
                log.warn("Séquestre {} d'un colis livré versé par l'auto-réparation", paymentId);
            } else if (outcome.failed()) {
                // Alerte LATE_RELEASE_FAILED levée (une seule) ; nouvel essai dans 1 h, dans la fenêtre.
                nextRelease.put(paymentId, now.plus(Duration.ofHours(1)));
            } else if (outcome.blockedByGuard()) {
                // La garde a alerté ; rien ne changera sans un admin : plus d'essai jusqu'au prochain démarrage.
                nextRelease.put(paymentId, now.plus(RELEASE_WINDOW));
            } else {
                nextRelease.remove(paymentId);
            }
        }
        if (!due.isEmpty()) {
            log.info("Auto-réparation des séquestres livrés non versés : {} tenté(s), {} versé(s)", due.size(), released);
        }
        return due.size();
    }

    /**
     * Lit les candidats page par page (plus récents d'abord) et garde ceux dont la relecture est
     * due, jusqu'à remplir le lot : les paiements en attente de relecture n'occupent jamais la place
     * des suivants.
     */
    private List<UUID> dueCandidates(BiFunction<Integer, Integer, List<UUID>> page, Predicate<UUID> isDue) {
        int pageSize = Math.max(1, batchSize * 5);
        Set<UUID> due = new LinkedHashSet<>();
        for (int p = 0; p < MAX_PAGES && due.size() < batchSize; p++) {
            List<UUID> ids = page.apply(p, pageSize);
            for (UUID id : ids) {
                if (due.size() >= batchSize) {
                    break;
                }
                if (isDue.test(id)) {
                    due.add(id);
                }
            }
            if (ids.size() < pageSize) {
                break;
            }
        }
        return List.copyOf(due);
    }

    private static boolean isDue(Map<UUID, Instant> agenda, UUID id, Instant now) {
        Instant at = agenda.get(id);
        return at == null || !at.isAfter(now);
    }

    /** Délai avant la prochaine relecture d'un paiement trouvé cohérent avec Stripe. */
    private Duration backoff(UUID paymentId, PaymentStripeResyncService.Result result, Instant now) {
        Duration age = paymentRepository.findById(paymentId)
                .map(p -> p.getCreatedAt() == null ? Duration.ZERO
                        : Duration.between(p.getCreatedAt().toInstant(ZoneOffset.UTC), now))
                .orElse(maxAge);
        String stripeStatus = result.before() == null ? null : result.before().stripeStatus();
        if (stripeStatus != null && NOT_ATTEMPTED.contains(stripeStatus) && age.compareTo(abandonedAfter) >= 0) {
            // Checkout abandonné : plus relu (l'entrée tombe avec la fenêtre max-age).
            return maxAge;
        }
        if (age.compareTo(Duration.ofHours(1)) < 0) {
            // Jamais « au prochain passage » : un lot de paiements récents cohérents laisse sa place aux suivants.
            return Duration.ofMinutes(30);
        }
        if (age.compareTo(Duration.ofHours(24)) < 0) {
            return Duration.ofHours(1);
        }
        return Duration.ofHours(6);
    }

    /** Pour les tests : prochaine relecture prévue d'un paiement PENDING. */
    Instant nextCheckOf(UUID paymentId) {
        return nextCheck.get(paymentId);
    }

    /** Pour les tests : prochain essai de versement prévu d'un séquestre livré. */
    Instant nextReleaseOf(UUID paymentId) {
        return nextRelease.get(paymentId);
    }
}
