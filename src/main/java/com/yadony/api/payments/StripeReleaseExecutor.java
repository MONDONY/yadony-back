package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Versement carte au voyageur en trois temps, sans connexion JDBC ni verrou de ligne tenu pendant
 * l'appel Stripe (PROD_CHECKLIST 9.23) :
 * <ol>
 *   <li><b>réserver</b> ({@link #reserve}, dans la transaction courte de l'appelant qui porte le
 *       claim atomique ESCROW → RELEASED) : la ligne {@link StripeReleaseIntentEntity} fige montant,
 *       devise, destination et charge source, avec un bail de {@link #LEASE} ;</li>
 *   <li><b>appeler</b> ({@link #execute}, hors transaction) : recherche d'un Transfer déjà émis
 *       ({@link StripeTransferLookup}), sinon {@code Transfer.create} avec la clé stable
 *       {@code transfer-<paymentId>} (ou capture legacy, clé {@code capture-<paymentId>}) ;</li>
 *   <li><b>finaliser</b> (transaction courte) : UPDATE conditionnel {@code PENDING → DONE}, identifiant
 *       du Transfer, audit et {@link PaymentReleasedEvent} — un seul traitement y parvient.</li>
 * </ol>
 *
 * <p>Issue de l'appel :
 * <ul>
 *   <li>Stripe <b>refuse</b> (4xx : requête invalide, compte, permission) : rien n'a été créé, la
 *       réservation passe REVERTED et le claim est annulé (RELEASED → ESCROW, seulement sans Transfer
 *       enregistré), comme l'ancien rollback ;</li>
 *   <li>issue <b>incertaine</b> (réseau, 5xx, limite de débit, clé d'idempotence en cours) : le
 *       paiement reste RELEASED, la réservation PENDING, et {@link StripeReleaseRecoveryJob} rejoue
 *       à l'identique (même clé, même montant ; la recherche Stripe retrouve un Transfer créé malgré
 *       l'erreur). Au bout de {@link #ALERT_AFTER_ATTEMPTS} tentatives, alerte
 *       {@code RELEASE_PENDING_<paymentId>}. Plus jamais d'annulation du claim alors qu'un Transfer a
 *       pu partir.</li>
 * </ul>
 */
@Component
public class StripeReleaseExecutor {

    private static final Logger log = LoggerFactory.getLogger(StripeReleaseExecutor.class);

    /**
     * Bail d'une réservation : au-delà, le job peut la reprendre. Plus long que le pire appel du
     * SDK (connexion 30 s + lecture 80 s, trois essais avec les relances de 9.24 : 5,5 min).
     */
    public static final Duration LEASE = Duration.ofMinutes(10);

    /** Préfixe (16) + UUID (36) = 52, sous la limite de 60 de {@code admin_alerts.type}. */
    public static final String PENDING_ALERT_PREFIX = "RELEASE_PENDING_";

    static final int ALERT_AFTER_ATTEMPTS = 3;
    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    public enum Result {
        /** Transfer (ou capture legacy) créé et finalisé par ce traitement. */
        RELEASED,
        /** Un Transfer existait déjà chez Stripe : base réalignée, aucun second Transfer. */
        REALIGNED,
        /** Issue incertaine : paiement RELEASED, réservation PENDING, reprise par le job. */
        PENDING_RETRY,
        /** Stripe a refusé sans rien créer : claim annulé, paiement ESCROW. */
        REVERTED,
        /** Une garde est apparue avant l'appel (aucun appel Stripe) : claim annulé, paiement ESCROW. */
        BLOCKED,
        /** Déjà finalisé ou annulé par un autre traitement, ou bail détenu ailleurs. */
        NOT_OWNED
    }

    public record Execution(Result result, String stripeObjectId, String error) {
        public boolean paid() {
            return result == Result.RELEASED || result == Result.REALIGNED;
        }
    }

    /** Ce que la réservation fige pour rejouer l'appel Stripe à l'identique. */
    public record ReleaseSpec(UUID paymentId, StripeReleaseIntentEntity.Kind kind, Long amountMinor, String currency,
                              String destination, String sourceTransaction, String source, String auditAction,
                              UUID bidId, UUID travelerId, UUID senderId, boolean guardsOverridden) {
        public ReleaseSpec(UUID paymentId, StripeReleaseIntentEntity.Kind kind, Long amountMinor, String currency,
                           String destination, String sourceTransaction, String source, String auditAction,
                           UUID bidId, UUID travelerId, UUID senderId) {
            this(paymentId, kind, amountMinor, currency, destination, sourceTransaction, source, auditAction,
                    bidId, travelerId, senderId, false);
        }
    }

    /**
     * Gardes revérifiées juste avant l'appel Stripe, dans une transaction courte.
     * @return la raison du blocage, {@code null} si le versement peut partir
     */
    public interface ReleaseGuard {
        String blockingReason(StripeReleaseIntentEntity intent);
    }

    /** Aucune garde (tests unitaires des autres composants). */
    static final ReleaseGuard NO_GUARD = intent -> null;

    private final StripeReleaseIntentRepository intentRepository;
    private final PaymentRepository paymentRepository;
    private final StripeTransferLookup transferLookup;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminAlertEscalator alertEscalator;
    private final TransactionOperations transaction;
    private final Clock clock;
    private final ReleaseGuard guard;

    @Autowired
    public StripeReleaseExecutor(StripeReleaseIntentRepository intentRepository, PaymentRepository paymentRepository,
                                 StripeTransferLookup transferLookup, AuditService auditService,
                                 ApplicationEventPublisher eventPublisher, AdminAlertEscalator alertEscalator,
                                 PlatformTransactionManager transactionManager, StripeReleaseGuard guard) {
        this(intentRepository, paymentRepository, transferLookup, auditService, eventPublisher, alertEscalator,
                EscrowCaptureService.requiresNew(transactionManager), Clock.systemUTC(), guard);
    }

    StripeReleaseExecutor(StripeReleaseIntentRepository intentRepository, PaymentRepository paymentRepository,
                          StripeTransferLookup transferLookup, AuditService auditService,
                          ApplicationEventPublisher eventPublisher, AdminAlertEscalator alertEscalator,
                          TransactionOperations transaction, Clock clock, ReleaseGuard guard) {
        this.intentRepository = intentRepository;
        this.paymentRepository = paymentRepository;
        this.transferLookup = transferLookup;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.alertEscalator = alertEscalator;
        this.transaction = transaction;
        this.clock = clock;
        this.guard = guard != null ? guard : NO_GUARD;
    }

    /**
     * Phase 1 : réserve le versement, dans la transaction de l'appelant qui vient de poser le claim
     * ESCROW → RELEASED (verrou de ligne du paiement tenu : aucune réservation concurrente). Une
     * réservation REVERTED est réutilisée ; une réservation DONE signifie qu'un versement est déjà
     * parti pour ce paiement : on refuse (l'exception annule le claim), jamais un second versement.
     */
    public StripeReleaseIntentEntity reserve(ReleaseSpec spec) {
        LocalDateTime now = now();
        StripeReleaseIntentEntity intent = intentRepository.findByPaymentId(spec.paymentId()).orElse(null);
        if (intent != null && intent.getStatus() != StripeReleaseIntentEntity.Status.REVERTED) {
            throw new IllegalStateException("Versement du paiement " + spec.paymentId() + " déjà réservé ("
                    + intent.getStatus() + ", " + intent.getStripeObjectId() + ") : aucun second versement");
        }
        if (intent == null) {
            intent = new StripeReleaseIntentEntity();
            intent.setPaymentId(spec.paymentId());
        }
        intent.setKind(spec.kind());
        intent.setStatus(StripeReleaseIntentEntity.Status.PENDING);
        intent.setAmountMinor(spec.amountMinor());
        intent.setCurrency(spec.currency());
        intent.setDestination(spec.destination());
        intent.setSourceTransaction(spec.sourceTransaction());
        intent.setSource(spec.source());
        intent.setAuditAction(spec.auditAction());
        intent.setBidId(spec.bidId());
        intent.setTravelerId(spec.travelerId());
        intent.setSenderId(spec.senderId());
        intent.setGuardsOverridden(spec.guardsOverridden());
        intent.setAttempts(1);
        intent.setLeaseUntil(now.plus(LEASE));
        intent.setLastError(null);
        intent.setStripeObjectId(null);
        StripeReleaseIntentEntity saved = intentRepository.save(intent);
        return saved != null ? saved : intent;
    }

    /**
     * Reprise par le job : prend le bail d'une réservation échue (transaction courte), puis appelle
     * Stripe et finalise. {@link Result#NOT_OWNED} si elle n'est plus à reprendre.
     */
    public Execution resume(UUID intentId) {
        LocalDateTime now = now();
        Boolean leased = transaction.execute(tx -> intentRepository.claimLease(intentId, now, now.plus(LEASE)) == 1);
        if (!Boolean.TRUE.equals(leased)) {
            return new Execution(Result.NOT_OWNED, null, null);
        }
        StripeReleaseIntentEntity intent = intentRepository.findById(intentId).orElse(null);
        if (intent == null) {
            return new Execution(Result.NOT_OWNED, null, null);
        }
        return execute(intent);
    }

    /**
     * Phases 2 et 3 : appel Stripe hors transaction, puis finalisation conditionnelle. L'appelant ne
     * doit tenir aucune transaction (sa connexion resterait empruntée pendant l'appel) : refusé
     * avant tout appel Stripe, la réservation est alors reprise par le job.
     */
    public Execution execute(StripeReleaseIntentEntity intent) {
        return execute(intent, null);
    }

    /**
     * Même chose avec le paiement déjà lu par l'appelant : seuls ses champs immuables sont utilisés
     * (PaymentIntent, montant, commission, devise, date de création), jamais son statut.
     */
    public Execution execute(StripeReleaseIntentEntity intent, PaymentEntity snapshot) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Appel Stripe du versement " + intent.getPaymentId()
                    + " demandé sous transaction : refusé (PROD_CHECKLIST 9.23)");
        }
        UUID paymentId = intent.getPaymentId();
        PaymentEntity payment = snapshot != null ? snapshot : paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.error("Versement réservé pour un paiement introuvable : {}", paymentId);
            return new Execution(Result.NOT_OWNED, null, "paiement introuvable");
        }
        try {
            if (intent.getKind() == StripeReleaseIntentEntity.Kind.LEGACY_CAPTURE) {
                return captureLegacy(intent, payment);
            }
            // La clé d'idempotence ne vit que 24 h : on cherche d'abord chez Stripe un Transfer déjà
            // émis pour ce paiement (essai précédent dont la réponse s'est perdue, Transfer d'avant
            // ce lot). S'il existe, la base est réalignée dessus, aucun second Transfer.
            Optional<String> existing;
            try {
                existing = transferLookup.findExistingTransfer(
                        paymentId, intent.getDestination(), payment.getCreatedAt());
            } catch (StripeException lookupFailure) {
                if (intent.getAttempts() <= 1) {
                    // Premier essai de cette réservation : aucun Transfer n'a pu être demandé par
                    // elle. Recherche impossible = on ne verse pas (fail-closed) et on rend le
                    // séquestre, comme avant ; l'auto-réparation retentera.
                    log.error("Versement du paiement {} : recherche Stripe d'un Transfer existant impossible ({})",
                            paymentId, lookupFailure.getMessage());
                    return revert(intent, "recherche Stripe impossible : "
                            + lookupFailure.getClass().getSimpleName() + ": " + lookupFailure.getMessage());
                }
                // Essai suivant : un Transfer a pu partir lors d'un essai précédent, la base ne
                // revient jamais en séquestre sur une recherche ratée. On rejouera.
                return retryLater(intent, "recherche Stripe impossible : "
                        + lookupFailure.getClass().getSimpleName() + ": " + lookupFailure.getMessage());
            }
            if (existing.isPresent()) {
                log.warn("Payment {}: Stripe Transfer {} already exists — no second Transfer, DB realigned",
                        paymentId, existing.get());
                return finalizeDone(intent, payment, existing.get(), true);
            }
            // Aucun Transfer chez Stripe : gardes revérifiées sous verrou juste avant l'appel.
            String blocked = guard.blockingReason(intent);
            if (blocked != null) {
                return blockedBeforeCall(intent, blocked);
            }
            Transfer transfer = Transfer.create(transferParams(intent),
                    RequestOptions.builder().setIdempotencyKey("transfer-" + paymentId).build());
            return finalizeDone(intent, payment, transfer != null ? transfer.getId() : null, false);
        } catch (StripeException e) {
            return onStripeFailure(intent, e);
        }
    }

    private Execution captureLegacy(StripeReleaseIntentEntity intent, PaymentEntity payment) throws StripeException {
        // Destination charge historique : capturer verse au voyageur (transfer_data posé à la création).
        PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
        String status = pi != null ? pi.getStatus() : null;
        if (pi == null || "canceled".equals(status) || "requires_payment_method".equals(status)) {
            return revert(intent, "PaymentIntent " + status + " : plus rien à capturer");
        }
        if (!EscrowCaptureService.STATUS_SUCCEEDED.equals(status)) {
            String blocked = guard.blockingReason(intent);
            if (blocked != null) {
                return blockedBeforeCall(intent, blocked);
            }
            // Clé d'idempotence stable : une reprise ne déclenche pas une seconde capture.
            pi.capture(PaymentIntentCaptureParams.builder().build(),
                    RequestOptions.builder().setIdempotencyKey("capture-" + intent.getPaymentId()).build());
        }
        return finalizeDone(intent, payment, payment.getStripePaymentIntentId(), false);
    }

    static TransferCreateParams transferParams(StripeReleaseIntentEntity intent) {
        UUID paymentId = intent.getPaymentId();
        TransferCreateParams.Builder builder = TransferCreateParams.builder()
                .setAmount(intent.getAmountMinor())
                .setCurrency(intent.getCurrency())
                .setDestination(intent.getDestination())
                .putMetadata("bid_id", intent.getBidId() != null ? intent.getBidId().toString() : "")
                .putMetadata("payment_id", paymentId != null ? paymentId.toString() : "")
                .putMetadata("source", String.valueOf(intent.getSource()));
        if (paymentId != null) {
            // Groupe stable, filtrable chez Stripe quel que soit le compte de destination.
            builder.setTransferGroup(StripeTransferLookup.transferGroup(paymentId));
        }
        if (intent.getSourceTransaction() != null && !intent.getSourceTransaction().isBlank()) {
            builder.setSourceTransaction(intent.getSourceTransaction());
        }
        return builder.build();
    }

    /** Phase 3 : un seul traitement fait passer la réservation en DONE, trace et notifie. */
    private Execution finalizeDone(StripeReleaseIntentEntity intent, PaymentEntity payment, String objectId,
                                   boolean realigned) {
        UUID paymentId = intent.getPaymentId();
        Boolean mine = transaction.execute(tx -> {
            if (intentRepository.markDone(intent.getId(), objectId) == 0) {
                return false;
            }
            if (intent.getKind() == StripeReleaseIntentEntity.Kind.TRANSFER && objectId != null) {
                paymentRepository.recordStripeTransferId(paymentId, objectId);
            }
            if (realigned) {
                Map<String, Object> audit = new LinkedHashMap<>();
                audit.put("paymentId", String.valueOf(paymentId));
                audit.put("bidId", String.valueOf(intent.getBidId()));
                audit.put("transferId", objectId);
                audit.put("source", intent.getSource());
                auditService.log("PAYMENT", paymentId, "TRANSFER_ALREADY_EXISTS_REALIGNED", intent.getBidId(), audit);
                return true;
            }
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("paymentId", String.valueOf(paymentId));
            audit.put("bidId", String.valueOf(intent.getBidId()));
            audit.put("piId", String.valueOf(payment.getStripePaymentIntentId()));
            audit.put("amount", payment.getAmount() != null ? payment.getAmount().toPlainString() : "null");
            audit.put("legacy", String.valueOf(intent.getKind() == StripeReleaseIntentEntity.Kind.LEGACY_CAPTURE));
            audit.put("source", intent.getSource());
            if (objectId != null && intent.getKind() == StripeReleaseIntentEntity.Kind.TRANSFER) {
                audit.put("transferId", objectId);
            }
            auditService.log("PAYMENT", paymentId, intent.getAuditAction(), intent.getBidId(), audit);
            // Publié dans la transaction : les écouteurs AFTER_COMMIT (cache, notification) partent au commit.
            if (intent.getBidId() != null && intent.getTravelerId() != null) {
                eventPublisher.publishEvent(PaymentReleasedEvent.card(intent.getBidId(), intent.getTravelerId(),
                        intent.getSenderId(), payment.getAmount(), payment.getCommissionAmount(), payment.getCurrency()));
            }
            return true;
        });
        if (!Boolean.TRUE.equals(mine)) {
            log.info("Versement du paiement {} déjà finalisé par un autre traitement", paymentId);
            return new Execution(Result.NOT_OWNED, objectId, null);
        }
        resolvePendingAlert(paymentId);
        log.info("Versement du paiement {} {} ({}, source {})", paymentId, realigned ? "réaligné" : "effectué",
                objectId, intent.getSource());
        return new Execution(realigned ? Result.REALIGNED : Result.RELEASED, objectId, null);
    }

    private Execution onStripeFailure(StripeReleaseIntentEntity intent, StripeException e) {
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (definitiveRefusal(e) && intent.getAttempts() <= 1) {
            // Premier essai : aucun appel antérieur dont l'issue serait inconnue, rien n'a été créé.
            log.error("Versement du paiement {} refusé par Stripe : {}", intent.getPaymentId(), error, e);
            return revert(intent, error);
        }
        if (definitiveRefusal(e)) {
            // Un essai précédent a pu créer le Transfer (issue inconnue) : jamais de retour en
            // séquestre. Réservation conservée, alerte immédiate, décision admin.
            log.error("Versement du paiement {} refusé par Stripe après un essai incertain : {}",
                    intent.getPaymentId(), error, e);
            return retryLater(intent, error, true);
        }
        return retryLater(intent, error, false);
    }

    /**
     * Une garde est apparue entre le claim et l'appel (aucun appel Stripe n'est parti). Premier
     * essai : rien n'a jamais été demandé à Stripe pour cette réservation, le claim est annulé
     * (ESCROW, la garde décide). Reprise : un essai précédent a pu créer un Transfer, le paiement
     * reste réservé et l'admin tranche (alerte).
     */
    private Execution blockedBeforeCall(StripeReleaseIntentEntity intent, String reason) {
        String error = "garde apparue avant l'appel Stripe : " + reason;
        log.warn("Versement du paiement {} bloqué avant l'appel Stripe : {}", intent.getPaymentId(), reason);
        raiseBlockedAlert(intent, reason);
        if (intent.getAttempts() > 1) {
            return retryLater(intent, error, true);
        }
        Execution reverted = revert(intent, error, "ESCROW_RELEASE_BLOCKED_BEFORE_STRIPE");
        return reverted.result() == Result.REVERTED ? new Execution(Result.BLOCKED, null, error) : reverted;
    }

    /** Préfixe (16) + UUID (36) = 52 caractères. */
    public static final String BLOCKED_ALERT_PREFIX = "RELEASE_BLOCKED_";

    private void raiseBlockedAlert(StripeReleaseIntentEntity intent, String reason) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paymentId", String.valueOf(intent.getPaymentId()));
        context.put("bidId", String.valueOf(intent.getBidId()));
        context.put("source", intent.getSource());
        context.put("reason", reason);
        try {
            alertEscalator.raiseOnce(BLOCKED_ALERT_PREFIX + intent.getPaymentId(),
                    "Versement du paiement " + intent.getPaymentId() + " bloqué juste avant l'appel Stripe (" + reason
                            + ") : aucun Transfer n'est parti, à trancher", context);
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {}{} non levée", BLOCKED_ALERT_PREFIX, intent.getPaymentId(), alertFailure);
        }
    }

    /** Issue incertaine : la réservation reste PENDING (paiement RELEASED), prochaine reprise différée. */
    private Execution retryLater(StripeReleaseIntentEntity intent, String error) {
        return retryLater(intent, error, false);
    }

    /** @param needsAdmin issue que seule une décision admin tranchera : alerte immédiate, reprise espacée */
    private Execution retryLater(StripeReleaseIntentEntity intent, String error, boolean needsAdmin) {
        int attempts = Math.max(1, intent.getAttempts());
        LocalDateTime retryAt = now().plus(needsAdmin ? MAX_BACKOFF : backoff(attempts));
        transaction.executeWithoutResult(tx -> intentRepository.recordRetry(intent.getId(), error, retryAt));
        log.warn("Versement du paiement {} incertain (essai {}) : {} — paiement RELEASED, reprise à {}",
                intent.getPaymentId(), attempts, error, retryAt);
        if (needsAdmin || attempts >= ALERT_AFTER_ATTEMPTS) {
            raisePendingAlert(intent, error, attempts);
        }
        return new Execution(Result.PENDING_RETRY, null, error);
    }

    /** Stripe a refusé sans rien créer : réservation REVERTED et claim annulé (RELEASED → ESCROW). */
    private Execution revert(StripeReleaseIntentEntity intent, String error) {
        return revert(intent, error, "ESCROW_RELEASE_REFUSED_BY_STRIPE");
    }

    private Execution revert(StripeReleaseIntentEntity intent, String error, String auditAction) {
        UUID paymentId = intent.getPaymentId();
        Boolean mine = transaction.execute(tx -> {
            if (intentRepository.markReverted(intent.getId(), error) == 0) {
                return false;
            }
            int reverted = paymentRepository.revertUnpaidReleaseClaim(paymentId);
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("paymentId", String.valueOf(paymentId));
            audit.put("bidId", String.valueOf(intent.getBidId()));
            audit.put("source", intent.getSource());
            audit.put("error", error);
            audit.put("claimReverted", String.valueOf(reverted == 1));
            auditService.log("PAYMENT", paymentId, auditAction, intent.getBidId(), audit);
            if (reverted == 0) {
                log.error("Versement du paiement {} refusé mais claim non annulé (statut changé entre-temps)",
                        paymentId);
            }
            return true;
        });
        if (!Boolean.TRUE.equals(mine)) {
            return new Execution(Result.NOT_OWNED, null, error);
        }
        resolvePendingAlert(paymentId);
        return new Execution(Result.REVERTED, null, error);
    }

    /**
     * Refus certain de Stripe (rien n'a été créé) : requête invalide (hors limite de débit), carte,
     * authentification, permission. Tout le reste (réseau, 5xx, limite de débit, clé d'idempotence
     * en cours ou réutilisée) est incertain ou transitoire : on rejoue, on n'annule jamais.
     */
    static boolean definitiveRefusal(StripeException e) {
        if (e instanceof RateLimitException || e instanceof IdempotencyException
                || e instanceof ApiConnectionException || e instanceof ApiException) {
            return false;
        }
        return e instanceof InvalidRequestException || e instanceof CardException
                || e instanceof AuthenticationException || e instanceof PermissionException;
    }

    static Duration backoff(int attempts) {
        long minutes = 5L << Math.min(Math.max(attempts - 1, 0), 4);
        Duration wait = Duration.ofMinutes(minutes);
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    private void raisePendingAlert(StripeReleaseIntentEntity intent, String error, int attempts) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paymentId", String.valueOf(intent.getPaymentId()));
        context.put("bidId", String.valueOf(intent.getBidId()));
        context.put("source", intent.getSource());
        context.put("attempts", String.valueOf(attempts));
        context.put("error", error);
        try {
            alertEscalator.raiseOnce(PENDING_ALERT_PREFIX + intent.getPaymentId(),
                    "Versement du paiement " + intent.getPaymentId() + " non confirmé par Stripe après " + attempts
                            + " essais (" + error + ") : le paiement est réservé (RELEASED), la reprise automatique "
                            + "continue avec la même clé ; vérifier le Transfer dans Stripe, ne pas forcer un second versement",
                    context);
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {}{} non levée", PENDING_ALERT_PREFIX, intent.getPaymentId(), alertFailure);
        }
    }

    private void resolvePendingAlert(UUID paymentId) {
        try {
            alertEscalator.resolveOpen(PENDING_ALERT_PREFIX + paymentId);
        } catch (RuntimeException e) {
            log.warn("Alerte {}{} non close : {}", PENDING_ALERT_PREFIX, paymentId, e.getMessage());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
