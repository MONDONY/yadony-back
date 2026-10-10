package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-réparation des paiements carte restés {@code PENDING} : toutes les 15 minutes, relit chez
 * Stripe les PaymentIntents des paiements PENDING de plus de 10 minutes et applique la même
 * resynchronisation que le bouton admin ({@link PaymentStripeResyncService#resyncAutomatically}) :
 * {@code requires_capture} → séquestre (et versement immédiat si le colis est déjà livré),
 * {@code canceled} → annulé, refus carte → échec. Audit {@code PAYMENT_AUTO_RESYNC_STRIPE} sans acteur.
 *
 * <p>Pourquoi : le passage en séquestre dépend du webhook {@code payment_intent.amount_capturable_updated}
 * (et, pour une négociation, du {@code /checkout} synchrone). Un webhook non abonné, perdu ou en
 * retard laissait le paiement PENDING ; une livraison intervenue entre-temps ne versait rien et
 * l'admin devait resynchroniser puis forcer le versement (staging, 04 au 10/10/2026).
 *
 * <p>Bornes, pour ne jamais inonder Stripe :
 * <ul>
 *   <li>au plus {@code batch-size} paiements par passage (20 par défaut), plus anciens d'abord ;</li>
 *   <li>fenêtre : créés il y a plus de {@code min-age} (10 min, le temps du webhook) et moins de
 *       {@code max-age} (7 jours, durée de vie d'une autorisation carte) ;</li>
 *   <li>un paiement trouvé cohérent est revu de moins en moins souvent (à chaque passage la
 *       première heure, puis toutes les heures, puis toutes les 6 h après 24 h) ;</li>
 *   <li>un checkout abandonné ({@code requires_payment_method}, {@code requires_confirmation},
 *       {@code requires_action}) de plus de {@code abandoned-after} (24 h) n'est plus relu : son
 *       sort reste aux traitements existants (expiration de la négociation, annulation) ;</li>
 *   <li>Stripe indisponible : le passage s'arrête au premier échec réseau.</li>
 * </ul>
 * L'agenda de relecture est en mémoire : un redémarrage relit au plus un lot de plus. Aucune
 * exclusion entre instances : la resynchronisation est idempotente (gardes atomiques, clés
 * d'idempotence Stripe), deux passages simultanés ne versent jamais deux fois.
 */
@Component
public class PendingCardPaymentAutoHealJob {

    private static final Logger log = LoggerFactory.getLogger(PendingCardPaymentAutoHealJob.class);

    /** Statuts Stripe d'un checkout jamais mené à terme par l'expéditeur. */
    static final Set<String> NOT_ATTEMPTED = Set.of("requires_payment_method", "requires_confirmation", "requires_action");

    private final PaymentRepository paymentRepository;
    private final PaymentStripeResyncService resyncService;
    private final boolean enabled;
    private final Duration minAge;
    private final Duration maxAge;
    private final Duration abandonedAfter;
    private final int batchSize;
    private final Clock clock;

    /** Prochaine relecture autorisée par paiement (absent = relu au prochain passage). */
    private final Map<UUID, Instant> nextCheck = new ConcurrentHashMap<>();

    @Autowired
    public PendingCardPaymentAutoHealJob(PaymentRepository paymentRepository, PaymentStripeResyncService resyncService,
                                         @Value("${yadony.payments.auto-heal.enabled:true}") boolean enabled,
                                         @Value("${yadony.payments.auto-heal.min-age:PT10M}") Duration minAge,
                                         @Value("${yadony.payments.auto-heal.max-age:P7D}") Duration maxAge,
                                         @Value("${yadony.payments.auto-heal.abandoned-after:PT24H}") Duration abandonedAfter,
                                         @Value("${yadony.payments.auto-heal.batch-size:20}") int batchSize) {
        this(paymentRepository, resyncService, enabled, minAge, maxAge, abandonedAfter, batchSize, Clock.systemUTC());
    }

    PendingCardPaymentAutoHealJob(PaymentRepository paymentRepository, PaymentStripeResyncService resyncService,
                                  boolean enabled, Duration minAge, Duration maxAge, Duration abandonedAfter,
                                  int batchSize, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.resyncService = resyncService;
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

    /** Un passage. @return le nombre de paiements relus chez Stripe */
    public int run() {
        Instant now = clock.instant();
        LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        nextCheck.values().removeIf(at -> at.isBefore(now.minus(maxAge)));

        // On lit plus large que le lot : les paiements en attente de relecture sont écartés en mémoire.
        List<UUID> candidates = paymentRepository.findPendingCardPaymentIds(
                nowUtc.minus(minAge), nowUtc.minus(maxAge), PageRequest.of(0, batchSize * 5));
        int checked = 0;
        int healed = 0;
        int released = 0;
        for (UUID paymentId : candidates) {
            if (checked >= batchSize) {
                break;
            }
            Instant due = nextCheck.get(paymentId);
            if (due != null && due.isAfter(now)) {
                continue;
            }
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
                if (e.getStatus() == org.springframework.http.HttpStatus.BAD_GATEWAY) {
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
            return Duration.ZERO;
        }
        if (age.compareTo(Duration.ofHours(24)) < 0) {
            return Duration.ofHours(1);
        }
        return Duration.ofHours(6);
    }

    /** Pour les tests : prochaine relecture prévue d'un paiement. */
    Instant nextCheckOf(UUID paymentId) {
        return nextCheck.get(paymentId);
    }
}
