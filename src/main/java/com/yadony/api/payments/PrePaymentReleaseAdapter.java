package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCancelParams;
import com.yadony.api.cancellation.PrePaymentReleasePort;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Côté argent de l'annulation avant paiement ({@link PrePaymentReleasePort}).
 *
 * <p><b>Carte</b> — Stripe fait foi. Tous les PaymentIntents du bid sont d'abord LUS, et la
 * décision n'est prise qu'ensuite : si l'un d'eux est déjà autorisé ({@code requires_capture}),
 * capturé ({@code succeeded}) ou en traitement ({@code processing}), on n'annule RIEN et on rend
 * {@link Outcome#ALREADY_PAID} / {@link Outcome#PAYMENT_IN_PROGRESS}. Un paiement autorisé a un
 * second écrivain en route (webhook {@code amount_capturable_updated}, {@code confirm-payment}) :
 * il rejoint le chemin existant d'annulation avec libération de l'autorisation
 * ({@code PUT /bids/{id}/cancel}), plutôt que deux transactions qui se disputent le même bid.
 * Sinon ({@code requires_payment_method}, {@code requires_confirmation}, {@code requires_action})
 * le PaymentIntent est annulé : plus aucune autorisation ne peut aboutir, la carte ne sera jamais
 * débitée. Un PaymentIntent déjà {@code canceled} est un no-op.
 *
 * <p><b>Mobile money</b> — mêmes garde-fous que {@code MobileMoneyBidPaymentService#expire} :
 * dépôt encore ouvert → {@link Outcome#PAYMENT_IN_PROGRESS} (l'expéditeur valide peut-être son
 * code sur son téléphone) ; dépôt {@code COMPLETED} ou paiement en séquestre →
 * {@link Outcome#ALREADY_PAID}. Sinon {@code markCancelledIfPending}, la même primitive atomique
 * que l'expiration : si un dépôt tardif aboutit malgré tout, {@code confirmEscrow} trouve un
 * paiement {@code CANCELLED} et le rembourse lui-même.
 *
 * <p>Aucun {@code save} de l'entité paiement : elle n'a ni {@code @Version} ni
 * {@code @DynamicUpdate}, seules des écritures ciblées sont sûres.
 */
@Component
public class PrePaymentReleaseAdapter implements PrePaymentReleasePort {

    private static final Logger log = LoggerFactory.getLogger(PrePaymentReleaseAdapter.class);

    static final String AUDIT_ACTION = "PAYMENT_CANCELLED_BEFORE_PAYMENT";

    /** États où l'argent est engagé : autorisé, capturé. */
    private static final Set<String> PAID_STATES = Set.of("requires_capture", "succeeded");

    private final PaymentRepository paymentRepository;
    private final StripeGateway stripeGateway;
    private final PawapayOperationService pawapayOperations;
    private final AuditService auditService;

    public PrePaymentReleaseAdapter(PaymentRepository paymentRepository, StripeGateway stripeGateway,
                                    PawapayOperationService pawapayOperations, AuditService auditService) {
        this.paymentRepository = paymentRepository;
        this.stripeGateway = stripeGateway;
        this.pawapayOperations = pawapayOperations;
        this.auditService = auditService;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome releaseBeforeCancellation(UUID bidId, String bidPaymentIntentId, UUID actorId) {
        Optional<PaymentEntity> payment = paymentRepository.findByBidIdForUpdate(bidId);
        if (payment.isPresent() && payment.get().getRail() == PaymentRail.PAWAPAY) {
            return releaseMobileMoney(payment.get(), actorId);
        }
        return releaseCard(payment.orElse(null), bidPaymentIntentId, actorId);
    }

    // ── Carte ───────────────────────────────────────────────────────────────

    private Outcome releaseCard(PaymentEntity payment, String bidPaymentIntentId, UUID actorId) {
        if (payment != null && (payment.getStatus() == PaymentStatus.ESCROW
                || payment.getStatus() == PaymentStatus.RELEASED)) {
            return Outcome.ALREADY_PAID;
        }

        Set<String> intentIds = new LinkedHashSet<>();
        if (payment != null && payment.getStripePaymentIntentId() != null) {
            intentIds.add(payment.getStripePaymentIntentId());
        }
        if (bidPaymentIntentId != null && !bidPaymentIntentId.isBlank()) {
            intentIds.add(bidPaymentIntentId);
        }

        // 1. Tout lire avant de rien annuler : annuler un premier PaymentIntent puis découvrir
        //    que le second est autorisé laisserait une annulation Stripe que le rollback de la
        //    transaction ne peut pas défaire.
        List<PaymentIntent> toCancel = new ArrayList<>();
        for (String intentId : intentIds) {
            PaymentIntent intent = retrieve(intentId);
            String status = intent.getStatus();
            if (PAID_STATES.contains(status)) {
                log.info("Annulation avant paiement refusée : PI {} déjà {}", intentId, status);
                return Outcome.ALREADY_PAID;
            }
            if ("processing".equals(status)) {
                return Outcome.PAYMENT_IN_PROGRESS;
            }
            if (!"canceled".equals(status)) {
                toCancel.add(intent);
            }
        }

        // 2. Annuler ce qui peut encore l'être.
        for (PaymentIntent intent : toCancel) {
            Outcome refused = cancel(intent);
            if (refused != null) {
                return refused;
            }
        }

        boolean paymentClosed = payment != null && payment.getStatus() == PaymentStatus.PENDING
                && paymentRepository.markCancelledIfPending(payment.getId()) == 1;
        if (paymentClosed) {
            audit(payment, actorId, Map.of("cancelledIntents", String.valueOf(toCancel.size())));
        }
        return toCancel.isEmpty() && !paymentClosed ? Outcome.NOTHING_TO_RELEASE : Outcome.RELEASED;
    }

    /** @return {@code null} si le PaymentIntent est annulé, sinon l'issue qui interdit l'annulation. */
    private Outcome cancel(PaymentIntent intent) {
        try {
            intent.cancel(PaymentIntentCancelParams.builder()
                    .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                    .build());
            log.info("PaymentIntent {} annulé : demande annulée par l'expéditeur avant paiement", intent.getId());
            return null;
        } catch (StripeException e) {
            // L'état a bougé entre la lecture et l'annulation (3DS validé à l'instant) : relire.
            String status = retrieve(intent.getId()).getStatus();
            if ("canceled".equals(status)) {
                return null;
            }
            if (PAID_STATES.contains(status)) {
                return Outcome.ALREADY_PAID;
            }
            if ("processing".equals(status)) {
                return Outcome.PAYMENT_IN_PROGRESS;
            }
            log.error("Annulation du PI {} impossible (statut {}) : {}", intent.getId(), status, e.getMessage());
            throw stripeError();
        }
    }

    private PaymentIntent retrieve(String intentId) {
        try {
            return stripeGateway.retrievePaymentIntent(intentId);
        } catch (StripeException e) {
            log.error("Lecture du PI {} impossible : {}", intentId, e.getMessage());
            throw stripeError();
        }
    }

    // ── Mobile money ────────────────────────────────────────────────────────

    private Outcome releaseMobileMoney(PaymentEntity payment, UUID actorId) {
        if (payment.getStatus() == PaymentStatus.ESCROW || payment.getStatus() == PaymentStatus.RELEASED) {
            return Outcome.ALREADY_PAID;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return Outcome.NOTHING_TO_RELEASE;
        }
        Optional<PawapayOperationEntity> deposit =
                pawapayOperations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT);
        if (deposit.map(o -> !o.getStatus().isFinal()).orElse(false)) {
            return Outcome.PAYMENT_IN_PROGRESS;
        }
        if (deposit.map(o -> o.getStatus() == PawapayOperationStatus.COMPLETED).orElse(false)) {
            // Encaissé, confirmation pas encore appliquée : jamais annulé en silence.
            return Outcome.ALREADY_PAID;
        }
        if (paymentRepository.markCancelledIfPending(payment.getId()) == 0) {
            return Outcome.ALREADY_PAID;
        }
        audit(payment, actorId, Map.of());
        return Outcome.RELEASED;
    }

    private void audit(PaymentEntity payment, UUID actorId, Map<String, String> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("rail", payment.getRail().name());
        payload.put("amount", payment.getAmount() != null ? payment.getAmount().toPlainString() : "");
        if (payment.getStripePaymentIntentId() != null) {
            payload.put("piId", payment.getStripePaymentIntentId());
        }
        payload.put("bidId", String.valueOf(payment.getBidId()));
        auditService.log("PAYMENT", payment.getId(), AUDIT_ACTION, actorId, payload);
    }

    private static YadonyBusinessException stripeError() {
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-error", "Stripe Error",
                "Impossible de joindre le service de paiement. Réessayez dans un instant.");
    }
}
