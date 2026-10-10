package com.yadony.api.payments;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Resynchronisation admin d'un paiement carte avec Stripe (source de vérité) :
 * {@code POST /admin/payments/{id}/resync-stripe}.
 *
 * <p>Relit le PaymentIntent et réaligne la base en RÉUTILISANT les traitements existants, jamais
 * par une écriture ad hoc :
 * <ul>
 *   <li>PENDING + {@code requires_capture} (ou {@code succeeded}) → traitement du webhook
 *       {@code amount_capturable_updated} ({@link PaymentService#applyPaymentEscrowActive}) :
 *       ESCROW + {@code PaymentEscrowReadyEvent}, donc capture d'une négociation par
 *       {@link NegotiationCaptureListener} après commit ;</li>
 *   <li>ESCROW + {@code requires_capture} dont la capture est due → {@link EscrowCaptureService}
 *       (même chemin que le listener : garde, clé {@code capture-<id>}, {@code captured_at}, audit) ;</li>
 *   <li>ESCROW + {@code succeeded} sans {@code captured_at} → {@code captured_at} enregistré ;</li>
 *   <li>PENDING + {@code canceled} → traitement de {@code payment_intent.canceled} ;</li>
 *   <li>PENDING + {@code requires_payment_method} après un échec de paiement → traitement de
 *       {@code payment_intent.payment_failed} ;</li>
 *   <li>déjà cohérent → aucune écriture ({@link Action#ALREADY_IN_SYNC}).</li>
 * </ul>
 * Colis déjà livré ({@code COMPLETED}) et paiement en séquestre après l'alignement : le versement
 * au voyageur part dans la foulée, par le chemin de la livraison ({@link DeliveredEscrowReleaser}),
 * et la réponse le dit ({@link Result#released()}, message « Colis déjà livré : versement envoyé
 * au voyageur »). La livraison ne repasse jamais : sans ce rattrapage, l'admin devait forcer le
 * versement à la main.
 *
 * <p>Le même traitement tourne sans admin, toutes les 15 minutes, sur les paiements carte restés
 * PENDING ({@link #resyncAutomatically}, {@link PendingCardPaymentAutoHealJob}).
 * Tout autre écart (autorisation expirée sur un séquestre, montant différent, PaymentIntent
 * introuvable, statut non géré) répond une erreur RFC 7807 sans aucune écriture. Rejouable :
 * un second appel trouve la base alignée et ne fait rien.
 */
@Service
public class PaymentStripeResyncService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStripeResyncService.class);

    static final String SOURCE = "admin-resync-stripe";
    static final String SOURCE_AUTO = "auto-resync-stripe";
    static final String AUDIT_ADMIN = "ADMIN_PAYMENT_RESYNC_STRIPE";
    static final String AUDIT_AUTO = "PAYMENT_AUTO_RESYNC_STRIPE";

    /** Ce que la resynchronisation a fait. */
    public enum Action {
        /** Base et Stripe déjà cohérents : rien n'a été écrit. */
        ALREADY_IN_SYNC,
        /** PENDING → ESCROW (traitement du webhook {@code amount_capturable_updated}). */
        ESCROW_ACTIVATED,
        /** Séquestre autorisé capturé sur le solde plateforme. */
        ESCROW_CAPTURED,
        /** PaymentIntent déjà capturé chez Stripe : {@code captured_at} enregistré en base. */
        CAPTURE_RECORDED,
        /** PENDING → FAILED (traitement du webhook {@code payment_failed}). */
        MARKED_FAILED,
        /** PENDING → CANCELLED (traitement du webhook {@code canceled}). */
        MARKED_CANCELLED,
        /**
         * Paiement déjà en séquestre et capturé, colis déjà livré : seul le versement au voyageur
         * restait à faire, il est parti. Ajouté après #487 : un back-office qui ne le connaît pas
         * affiche le code brut et le message.
         */
        ESCROW_RELEASED
    }

    /** État d'un paiement vu par la base et par Stripe. */
    public record Snapshot(String status, Instant capturedAt, String stripeChargeId,
                           String stripeStatus, Long amountCapturable) {}

    /**
     * @param released vrai si le versement au voyageur est parti pendant cette resynchronisation
     *                 (colis déjà livré)
     */
    public record Result(UUID paymentId, String paymentIntentId, Action action,
                         Snapshot before, Snapshot after, String message, boolean released) {
        public Result(UUID paymentId, String paymentIntentId, Action action,
                      Snapshot before, Snapshot after, String message) {
            this(paymentId, paymentIntentId, action, before, after, message, false);
        }

        public boolean changed() {
            return action != Action.ALREADY_IN_SYNC;
        }
    }

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final EscrowCaptureService escrowCapture;
    private final BidRepository bidRepository;
    private final AuditService auditService;
    private final DeliveredEscrowReleaser deliveredRelease;
    private final TransactionTemplate transaction;

    public PaymentStripeResyncService(PaymentRepository paymentRepository, PaymentService paymentService,
                                      EscrowCaptureService escrowCapture, BidRepository bidRepository,
                                      AuditService auditService, DeliveredEscrowReleaser deliveredRelease,
                                      PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.escrowCapture = escrowCapture;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
        this.deliveredRelease = deliveredRelease;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Décision prise sur l'état local relu après l'appel Stripe. */
    private record Step(Action action, String message, boolean captureAfterCommit, boolean releaseAfterCommit) {
        Step(Action action, String message, boolean captureAfterCommit) {
            this(action, message, captureAfterCommit, false);
        }
    }

    /**
     * Sans transaction englobante, volontairement : le PaymentIntent est lu chez Stripe AVANT
     * toute lecture de l'entité qui sera écrite (aucun {@code save} d'une entité chargée avant un
     * appel réseau) ; l'alignement local se fait dans une transaction courte qui relit le paiement ;
     * une capture éventuelle part ensuite, dans sa propre transaction ({@link EscrowCaptureService}),
     * une fois le passage en séquestre commité.
     */
    public Result resync(UUID paymentId, UUID adminId) {
        return resync(paymentId, adminId, SOURCE, AUDIT_ADMIN);
    }

    /**
     * Même resynchronisation, lancée par le système ({@link PendingCardPaymentAutoHealJob}) : audit
     * {@code PAYMENT_AUTO_RESYNC_STRIPE} sans acteur, source {@code auto-resync-stripe}.
     */
    public Result resyncAutomatically(UUID paymentId) {
        return resync(paymentId, null, SOURCE_AUTO, AUDIT_AUTO);
    }

    private Result resync(UUID paymentId, UUID actorId, String source, String auditAction) {
        PaymentEntity initial = load(paymentId);
        String piId = initial.getStripePaymentIntentId();
        if (initial.getRail() != PaymentRail.STRIPE || piId == null || piId.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "not-a-card-payment",
                    "Not A Card Payment", "Ce paiement n'a pas de PaymentIntent Stripe : rien à resynchroniser");
        }

        PaymentIntent pi = retrieve(piId);
        String stripeStatus = pi.getStatus();
        Snapshot before = snapshot(initial, stripeStatus, pi.getAmountCapturable());

        Step step = transaction.execute(tx -> alignLocally(load(paymentId), pi));
        Action action = step.action();
        String message = step.message();
        String afterStripeStatus = stripeStatus;
        Long afterCapturable = pi.getAmountCapturable();

        boolean delivered = step.releaseAfterCommit();
        boolean captureFailed = false;
        if (step.captureAfterCommit()) {
            boolean alreadyChanged = action != Action.ALREADY_IN_SYNC;
            try {
                escrowCapture.ensureCaptured(paymentId, source);
                afterStripeStatus = "succeeded";
                afterCapturable = 0L;
                if (!alreadyChanged) {
                    action = Action.ESCROW_CAPTURED;
                    message = delivered
                            ? "Séquestre capturé sur le solde plateforme"
                            : "Séquestre capturé sur le solde plateforme : le voyageur sera payé à la livraison";
                } else {
                    message = message + " ; séquestre capturé sur le solde plateforme";
                }
            } catch (EscrowCaptureService.EscrowCaptureException e) {
                if (delivered) {
                    // Colis déjà livré : la livraison ne repassera pas, rien ne retentera seul.
                    deliveredRelease.reportFailure(load(paymentId), null,
                            "capture du séquestre impossible (" + e.getMessage() + ")", source);
                }
                if (!alreadyChanged) {
                    throw new YadonyBusinessException(HttpStatus.CONFLICT, "escrow-capture-failed",
                            "Escrow Capture Failed",
                            "Capture impossible (" + e.getMessage() + ") : rien n'a été écrit, "
                                    + "le paiement reste en séquestre");
                }
                // Le passage en séquestre est commité et juste : seule la capture reste à faire.
                captureFailed = true;
                message = delivered
                        ? join(message, notReleasedMessage("capture du séquestre impossible (" + e.getMessage() + ")"))
                        : message + " ; capture impossible pour l'instant (" + e.getMessage()
                                + "), alerte ESCROW_CAPTURE_FAILED levée, l'encaissement sera retenté à la livraison";
            }
        }

        boolean released = false;
        if (delivered && !captureFailed) {
            DeliveredEscrowReleaser.LateRelease release = deliveredRelease.releaseIfDelivered(paymentId, source);
            EscrowReleaseOutcome outcome = release.outcome();
            if (outcome.released()) {
                released = true;
                afterStripeStatus = "succeeded";
                afterCapturable = 0L;
                if (action == Action.ALREADY_IN_SYNC) {
                    action = Action.ESCROW_RELEASED;
                }
            }
            if (outcome.failed()) {
                // Alerte LATE_RELEASE_FAILED levée par le releaser ; le paiement reste en séquestre.
                message = join(message, notReleasedMessage(release.failure()));
            } else if (!outcome.message().isEmpty()) {
                message = join(message, outcome.message());
            }
        }

        Snapshot after = snapshot(load(paymentId), afterStripeStatus, afterCapturable);
        if (action != Action.ALREADY_IN_SYNC) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("piId", piId);
            audit.put("action", action.name());
            audit.put("before", snapshotMap(before));
            audit.put("after", snapshotMap(after));
            audit.put("released", released);
            auditService.log("PAYMENT", paymentId, auditAction, actorId, audit);
            log.info("Paiement {} resynchronisé avec Stripe ({}, acteur {}) : {}{}", paymentId, source,
                    actorId == null ? "système" : actorId, action, released ? ", versement envoyé" : "");
        }
        return new Result(paymentId, piId, action, before, after, message, released);
    }

    private static String join(String message, String addition) {
        return message == null || message.isBlank() ? addition : message + " ; " + addition;
    }

    /** Colis livré, versement automatique en échec : ce que l'admin doit faire. */
    static String notReleasedMessage(String reason) {
        return "Colis livré mais versement impossible : " + reason
                + ". Utilisez « Forcer le versement » après correction";
    }

    private PaymentEntity load(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "payment-not-found",
                        "Not Found", "Paiement introuvable"));
    }

    /** Alignement local, dans la transaction courte, sur l'entité relue après l'appel Stripe. */
    private Step alignLocally(PaymentEntity payment, PaymentIntent pi) {
        UUID paymentId = payment.getId();
        String stripeStatus = pi.getStatus();
        PaymentStatus status = payment.getStatus();
        if (status == PaymentStatus.PENDING || status == PaymentStatus.ESCROW) {
            requireSameAmount(payment, pi);
        }
        switch (status) {
            case PENDING -> {
                switch (stripeStatus) {
                    case "requires_capture", "succeeded" -> {
                        boolean succeeded = "succeeded".equals(stripeStatus);
                        if (deliveredRelease.deliveredBidOf(payment).isPresent()) {
                            // Colis déjà livré : la livraison n'a rien versé (paiement PENDING) et ne
                            // repassera pas. On capture (si besoin) puis on verse nous-mêmes après le
                            // commit ; les écouteurs asynchrones du passage en séquestre s'effacent.
                            paymentService.applyPaymentEscrowActive(pi, true);
                            if (succeeded) {
                                paymentRepository.markCapturedIfEscrow(paymentId, Instant.now());
                            }
                            boolean captureNow = !succeeded
                                    && EscrowCaptureService.captureDue(payment, bidStatus(payment));
                            return new Step(Action.ESCROW_ACTIVATED,
                                    "Paiement passé en séquestre comme à la réception du webhook Stripe",
                                    captureNow, true);
                        }
                        paymentService.applyPaymentEscrowActive(pi);
                        if (succeeded) {
                            // Déjà capturé chez Stripe : la trace locale suit, par écriture ciblée.
                            paymentRepository.markCapturedIfEscrow(paymentId, Instant.now());
                        }
                        String message = "Paiement passé en séquestre comme à la réception du webhook Stripe";
                        boolean captureNow = false;
                        if (!succeeded) {
                            if (payment.getBidId() == null) {
                                message += payment.getNegotiationThreadId() != null
                                        ? " ; la capture de la négociation part dans la foulée"
                                        : "";
                            } else if (EscrowCaptureService.captureDue(payment, bidStatus(payment))) {
                                // Colis classique déjà accepté : BidAcceptedEvent est passé sans
                                // capture (paiement encore PENDING) ; on capture comme l'aurait fait
                                // l'acceptation, après le commit du séquestre.
                                captureNow = true;
                            } else {
                                message += " ; l'encaissement aura lieu à l'acceptation du colis par le voyageur";
                            }
                        }
                        return new Step(Action.ESCROW_ACTIVATED, message, captureNow);
                    }
                    case "canceled" -> {
                        paymentService.applyPaymentIntentCanceled(pi);
                        return new Step(Action.MARKED_CANCELLED,
                                "PaymentIntent annulé chez Stripe : paiement marqué annulé", false);
                    }
                    case "requires_payment_method" -> {
                        if (pi.getLastPaymentError() != null) {
                            paymentService.applyPaymentFailed(pi);
                            return new Step(Action.MARKED_FAILED,
                                    "Paiement refusé chez Stripe : paiement marqué en échec", false);
                        }
                        return new Step(Action.ALREADY_IN_SYNC,
                                "Paiement pas encore tenté par l'expéditeur : base déjà à jour", false);
                    }
                    case "requires_confirmation", "requires_action", "processing" -> {
                        return new Step(Action.ALREADY_IN_SYNC,
                                "Paiement en cours chez Stripe (" + stripeStatus + ") : base déjà à jour", false);
                    }
                    default -> throw unsupported(payment, stripeStatus);
                }
            }
            case ESCROW -> {
                switch (stripeStatus) {
                    case "requires_capture" -> {
                        boolean delivered = deliveredRelease.deliveredBidOf(payment).isPresent();
                        if (!EscrowCaptureService.captureDue(payment, bidStatus(payment))) {
                            if (delivered) {
                                // Legacy : la capture fait partie du versement.
                                return new Step(Action.ALREADY_IN_SYNC, "", false, true);
                            }
                            return new Step(Action.ALREADY_IN_SYNC, payment.isLegacyDestinationCharge()
                                    ? "Séquestre legacy : la capture se fait à la livraison, base déjà à jour"
                                    : "Autorisation normale : la capture se fera à l'acceptation du colis", false);
                        }
                        return new Step(Action.ALREADY_IN_SYNC, "", true, delivered);
                    }
                    case "succeeded" -> {
                        boolean delivered = deliveredRelease.deliveredBidOf(payment).isPresent();
                        if (payment.getCapturedAt() == null) {
                            paymentRepository.markCapturedIfEscrow(paymentId, Instant.now());
                            return new Step(Action.CAPTURE_RECORDED,
                                    "Déjà capturé chez Stripe : date de capture enregistrée", false, delivered);
                        }
                        if (delivered) {
                            return new Step(Action.ALREADY_IN_SYNC, "", false, true);
                        }
                        return new Step(Action.ALREADY_IN_SYNC, "Séquestre capturé : base déjà à jour", false);
                    }
                    case "canceled" -> throw new YadonyBusinessException(HttpStatus.CONFLICT,
                            "authorization-expired", "Authorization Expired",
                            "L'autorisation carte a expiré ou a été annulée chez Stripe : plus rien à capturer. "
                                    + "Le paiement reste en séquestre, à rembourser ou à trancher à la main");
                    default -> throw unsupported(payment, stripeStatus);
                }
            }
            case RELEASED -> {
                if (!"succeeded".equals(stripeStatus)) throw unsupported(payment, stripeStatus);
                return new Step(Action.ALREADY_IN_SYNC, "Paiement versé et encaissé : base déjà à jour", false);
            }
            case REFUNDED -> {
                if (!"succeeded".equals(stripeStatus) && !"canceled".equals(stripeStatus)) {
                    throw unsupported(payment, stripeStatus);
                }
                return new Step(Action.ALREADY_IN_SYNC, "Paiement remboursé : base déjà à jour", false);
            }
            case CANCELLED, FAILED -> {
                if ("succeeded".equals(stripeStatus) || "requires_capture".equals(stripeStatus)) {
                    throw unsupported(payment, stripeStatus);
                }
                return new Step(Action.ALREADY_IN_SYNC, "Paiement clos sans encaissement : base déjà à jour", false);
            }
            default -> throw unsupported(payment, stripeStatus);
        }
    }

    private PaymentIntent retrieve(String piId) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(piId,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge").build(), null);
            if (pi == null || pi.getStatus() == null) {
                throw notFound(piId);
            }
            return pi;
        } catch (InvalidRequestException e) {
            if ("resource_missing".equals(e.getCode()) || Integer.valueOf(404).equals(e.getStatusCode())) {
                throw notFound(piId);
            }
            throw stripeUnavailable(e);
        } catch (StripeException e) {
            throw stripeUnavailable(e);
        }
    }

    private static YadonyBusinessException notFound(String piId) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "payment-intent-not-found",
                "Payment Intent Not Found", "PaymentIntent " + piId + " introuvable chez Stripe : rien n'a été écrit");
    }

    private static YadonyBusinessException stripeUnavailable(StripeException e) {
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-unavailable", "Stripe Error",
                "Stripe n'a pas pu être interrogé (" + e.getMessage() + ") : réessayer plus tard");
    }

    private static YadonyBusinessException unsupported(PaymentEntity payment, String stripeStatus) {
        return new YadonyBusinessException(HttpStatus.CONFLICT, "resync-not-supported", "Resync Not Supported",
                "Écart non géré automatiquement : base " + payment.getStatus() + ", Stripe " + stripeStatus
                        + ". Rien n'a été écrit, à trancher à la main");
    }

    private static void requireSameAmount(PaymentEntity payment, PaymentIntent pi) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(payment.getCurrency());
        long expected = CurrencyAmount.of(payment.getAmount(), currency).minor();
        if (pi.getAmount() == null || pi.getAmount() != expected || !currency.code().equalsIgnoreCase(pi.getCurrency())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "amount-mismatch", "Amount Mismatch",
                    "Montant Stripe " + pi.getAmount() + " " + pi.getCurrency() + " différent du montant attendu "
                            + expected + " " + currency.code() + " : rien n'a été écrit, à trancher à la main");
        }
    }

    private BidStatus bidStatus(PaymentEntity payment) {
        if (payment.getBidId() == null) {
            return null;
        }
        return bidRepository.findById(payment.getBidId()).map(BidEntity::getStatus).orElse(null);
    }

    private static Snapshot snapshot(PaymentEntity payment, String stripeStatus, Long amountCapturable) {
        return new Snapshot(payment.getStatus().name(), payment.getCapturedAt(), payment.getStripeChargeId(),
                stripeStatus, amountCapturable);
    }

    private static Map<String, Object> snapshotMap(Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s.status());
        m.put("capturedAt", String.valueOf(s.capturedAt()));
        m.put("stripeStatus", String.valueOf(s.stripeStatus()));
        return m;
    }
}
