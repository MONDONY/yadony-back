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
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Tout autre écart (autorisation expirée sur un séquestre, montant différent, PaymentIntent
 * introuvable, statut non géré) répond une erreur RFC 7807 sans aucune écriture. Rejouable :
 * un second appel trouve la base alignée et ne fait rien.
 */
@Service
public class PaymentStripeResyncService {

    private static final Logger log = LoggerFactory.getLogger(PaymentStripeResyncService.class);

    static final String SOURCE = "admin-resync-stripe";

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
        MARKED_CANCELLED
    }

    /** État d'un paiement vu par la base et par Stripe. */
    public record Snapshot(String status, Instant capturedAt, String stripeChargeId,
                           String stripeStatus, Long amountCapturable) {}

    public record Result(UUID paymentId, String paymentIntentId, Action action,
                         Snapshot before, Snapshot after, String message) {
        public boolean changed() {
            return action != Action.ALREADY_IN_SYNC;
        }
    }

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final EscrowCaptureService escrowCapture;
    private final BidRepository bidRepository;
    private final AuditService auditService;
    private final EntityManager entityManager;

    public PaymentStripeResyncService(PaymentRepository paymentRepository, PaymentService paymentService,
                                      EscrowCaptureService escrowCapture, BidRepository bidRepository,
                                      AuditService auditService, EntityManager entityManager) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.escrowCapture = escrowCapture;
        this.bidRepository = bidRepository;
        this.auditService = auditService;
        this.entityManager = entityManager;
    }

    @Transactional
    public Result resync(UUID paymentId, UUID adminId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "payment-not-found",
                        "Not Found", "Paiement introuvable"));
        String piId = payment.getStripePaymentIntentId();
        if (payment.getRail() != PaymentRail.STRIPE || piId == null || piId.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "not-a-card-payment",
                    "Not A Card Payment", "Ce paiement n'a pas de PaymentIntent Stripe : rien à resynchroniser");
        }

        PaymentIntent pi = retrieve(piId);
        String stripeStatus = pi.getStatus();
        Snapshot before = snapshot(payment, stripeStatus, pi.getAmountCapturable());

        PaymentStatus status = payment.getStatus();
        if (status == PaymentStatus.PENDING || status == PaymentStatus.ESCROW) {
            requireSameAmount(payment, pi);
        }

        Action action;
        String message;
        String afterStripeStatus = stripeStatus;
        switch (status) {
            case PENDING -> {
                switch (stripeStatus) {
                    case "requires_capture", "succeeded" -> {
                        paymentService.applyPaymentEscrowActive(pi);
                        if ("succeeded".equals(stripeStatus) && payment.getStatus() == PaymentStatus.ESCROW
                                && payment.getCapturedAt() == null) {
                            // Déjà capturé chez Stripe : la trace locale suit, comme pour un séquestre.
                            payment.setCapturedAt(Instant.now());
                            paymentRepository.save(payment);
                        }
                        action = Action.ESCROW_ACTIVATED;
                        message = "Paiement passé en séquestre comme à la réception du webhook Stripe"
                                + (payment.getNegotiationThreadId() != null && "requires_capture".equals(stripeStatus)
                                ? " ; la capture de la négociation part dans la foulée" : "");
                    }
                    case "canceled" -> {
                        paymentService.applyPaymentIntentCanceled(pi);
                        action = Action.MARKED_CANCELLED;
                        message = "PaymentIntent annulé chez Stripe : paiement marqué annulé";
                    }
                    case "requires_payment_method" -> {
                        if (pi.getLastPaymentError() != null) {
                            paymentService.applyPaymentFailed(pi);
                            action = Action.MARKED_FAILED;
                            message = "Paiement refusé chez Stripe : paiement marqué en échec";
                        } else {
                            action = Action.ALREADY_IN_SYNC;
                            message = "Paiement pas encore tenté par l'expéditeur : base déjà à jour";
                        }
                    }
                    case "requires_confirmation", "requires_action", "processing" -> {
                        action = Action.ALREADY_IN_SYNC;
                        message = "Paiement en cours chez Stripe (" + stripeStatus + ") : base déjà à jour";
                    }
                    default -> throw unsupported(payment, stripeStatus);
                }
            }
            case ESCROW -> {
                switch (stripeStatus) {
                    case "requires_capture" -> {
                        if (!EscrowCaptureService.captureDue(payment, bidStatus(payment))) {
                            action = Action.ALREADY_IN_SYNC;
                            message = payment.isLegacyDestinationCharge()
                                    ? "Séquestre legacy : la capture se fait à la livraison, base déjà à jour"
                                    : "Autorisation normale : la capture se fera à l'acceptation du colis";
                        } else {
                            try {
                                escrowCapture.ensureCaptured(paymentId, SOURCE);
                            } catch (EscrowCaptureService.EscrowCaptureException e) {
                                throw new YadonyBusinessException(HttpStatus.CONFLICT, "escrow-capture-failed",
                                        "Escrow Capture Failed",
                                        "Capture impossible (" + e.getMessage() + ") : rien n'a été écrit, "
                                                + "le paiement reste en séquestre");
                            }
                            // La capture a écrit dans sa propre transaction : relire la ligne.
                            entityManager.refresh(payment);
                            afterStripeStatus = "succeeded";
                            action = Action.ESCROW_CAPTURED;
                            message = "Séquestre capturé sur le solde plateforme : le voyageur sera payé à la livraison";
                        }
                    }
                    case "succeeded" -> {
                        if (payment.getCapturedAt() == null) {
                            paymentRepository.markCapturedIfEscrow(paymentId, Instant.now());
                            entityManager.refresh(payment);
                            action = Action.CAPTURE_RECORDED;
                            message = "Déjà capturé chez Stripe : date de capture enregistrée";
                        } else {
                            action = Action.ALREADY_IN_SYNC;
                            message = "Séquestre capturé : base déjà à jour";
                        }
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
                action = Action.ALREADY_IN_SYNC;
                message = "Paiement versé et encaissé : base déjà à jour";
            }
            case REFUNDED -> {
                if (!"succeeded".equals(stripeStatus) && !"canceled".equals(stripeStatus)) {
                    throw unsupported(payment, stripeStatus);
                }
                action = Action.ALREADY_IN_SYNC;
                message = "Paiement remboursé : base déjà à jour";
            }
            case CANCELLED, FAILED -> {
                if ("succeeded".equals(stripeStatus) || "requires_capture".equals(stripeStatus)) {
                    throw unsupported(payment, stripeStatus);
                }
                action = Action.ALREADY_IN_SYNC;
                message = "Paiement clos sans encaissement : base déjà à jour";
            }
            default -> throw unsupported(payment, stripeStatus);
        }

        Snapshot after = snapshot(payment, afterStripeStatus,
                action == Action.ESCROW_CAPTURED ? Long.valueOf(0L) : pi.getAmountCapturable());
        if (action != Action.ALREADY_IN_SYNC) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("piId", piId);
            audit.put("action", action.name());
            audit.put("before", snapshotMap(before));
            audit.put("after", snapshotMap(after));
            auditService.log("PAYMENT", paymentId, "ADMIN_PAYMENT_RESYNC_STRIPE", adminId, audit);
            log.info("Paiement {} resynchronisé avec Stripe par l'admin {} : {}", paymentId, adminId, action);
        }
        return new Result(paymentId, piId, action, before, after, message);
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
