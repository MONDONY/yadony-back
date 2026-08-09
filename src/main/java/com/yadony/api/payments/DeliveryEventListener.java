package com.yadony.api.payments;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.TransferCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Story 6.4 / bid-checkout-payment-first — Listens to DeliveryConfirmedEvent and
 * releases the Stripe escrow.
 *
 * Two paths depending on payment.legacy_destination_charge:
 *  - legacy=true  : destination charge model — capture the PaymentIntent (Stripe routes
 *                   funds to the traveler's Connect account via transfer_data set at PI
 *                   creation).
 *  - legacy=false : separate charges-and-transfers — the PI was already captured at
 *                   acceptation by BidAcceptedEventListener, so funds are on the platform
 *                   balance. Trigger a Transfer to the traveler's Connect account.
 *
 * Cross-package communication via Spring Events only.
 */
@Component
public class DeliveryEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventListener.class);

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final BidRepository bidRepository;
    private final AdminAlertService adminAlert;

    public DeliveryEventListener(PaymentRepository paymentRepository,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 ApplicationEventPublisher eventPublisher,
                                 BidRepository bidRepository,
                                 AdminAlertService adminAlert) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.bidRepository = bidRepository;
        this.adminAlert = adminAlert;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDeliveryConfirmed(DeliveryConfirmedEvent event) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid != null && bid.getPaymentMethod() == PaymentMethod.CASH) {
            log.debug("CASH bid {} — no Stripe escrow to release", event.getBidId());
            return;
        }

        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(event.getBidId());

        // Negotiation / dedicated-trip escrow is keyed on the negotiation thread
        // (bid_id = NULL) — the bid is materialised after payment. Fall back to the
        // thread payment so these escrows are released to the traveler too; otherwise
        // findByBidId returns empty and the payout is silently skipped.
        if (paymentOpt.isEmpty() && bid != null && bid.getLinkedNegotiationThreadId() != null) {
            paymentOpt = paymentRepository.findByNegotiationThreadId(bid.getLinkedNegotiationThreadId());
        }

        if (paymentOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent received for bidId={} but no payment found — skipping",
                    event.getBidId());
            return;
        }

        PaymentEntity payment = paymentOpt.get();

        if (payment.getStatus() != PaymentStatus.ESCROW) {
            log.info("Payment {} for bid {} has status {} — skipping escrow release",
                    payment.getId(), event.getBidId(), payment.getStatus());
            return;
        }

        if (payment.isDisputed()) {
            log.warn("Payment {} for bid {} is under chargeback dispute — blocking transfer",
                    payment.getId(), event.getBidId());
            auditService.log("PAYMENT", payment.getId(), "DELIVERY_TRANSFER_BLOCKED_CHARGEBACK",
                    event.getBidId(), Map.of("bidId", event.getBidId().toString()));
            adminAlert.raise("CHARGEBACK_TRANSFER_BLOCKED",
                    "Tentative de liberation escrow bloquee — litige ouvert sur payment " + payment.getId(),
                    Map.of("paymentId", payment.getId().toString(), "bidId", event.getBidId().toString()));
            return;
        }

        // Claim atomique ESCROW → RELEASED avant les appels Stripe : empêche un
        // double versement (double capture / double Transfer) si l'événement de
        // livraison est traité deux fois en parallèle.
        int claimed = paymentRepository.markReleasedIfEscrow(
                payment.getId(), LocalDateTime.now(ZoneOffset.UTC));
        if (claimed == 0) {
            log.info("Payment {} for bid {} already left ESCROW — skipping release",
                    payment.getId(), event.getBidId());
            return;
        }

        try {
            if (payment.isLegacyDestinationCharge()) {
                releaseLegacy(payment);
            } else {
                releaseV2(payment, event);
            }
        } catch (StripeException e) {
            log.error("Escrow release failed for payment {} (bid={}, legacy={}): {}",
                    payment.getId(), event.getBidId(),
                    payment.isLegacyDestinationCharge(), e.getMessage(), e);
            // Rollback de la transaction REQUIRES_NEW : le claim ESCROW → RELEASED
            // est annulé, le paiement reste en ESCROW — le scheduler admin J+48
            // garde la main pour retenter la libération.
            throw new IllegalStateException(
                    "Stripe escrow release failed for payment " + payment.getId(), e);
        }

        String action = payment.isLegacyDestinationCharge()
                ? "ESCROW_RELEASED_LEGACY"
                : "ESCROW_RELEASED_TRANSFER";
        // Use event.getBidId() (the delivered bid) for the audit actor/payload: a
        // negotiation/thread payment has a NULL payment.getBidId(), which would both
        // lose the bid reference and NPE on toString().
        auditService.log(
                "PAYMENT",
                payment.getId(),
                action,
                event.getBidId(),
                Map.of(
                        "bidId", event.getBidId().toString(),
                        "piId", payment.getStripePaymentIntentId(),
                        "amount", payment.getAmount().toPlainString(),
                        "legacy", String.valueOf(payment.isLegacyDestinationCharge())
                )
        );

        log.info("Escrow released for payment {} (bid={}, legacy={})",
                payment.getId(), event.getBidId(), payment.isLegacyDestinationCharge());

        // Notify traveler of payout (Story 8.2). event.getBidId() — payment.getBidId()
        // is NULL for negotiation/thread payments.
        eventPublisher.publishEvent(new PaymentReleasedEvent(
                event.getBidId(), event.getTravelerId(), event.getSenderId(), payment.getAmount()));
    }

    private void releaseLegacy(PaymentEntity payment) throws StripeException {
        // Old destination-charge model: capture the PaymentIntent. Stripe transfers
        // funds directly to the traveler's Connect account because transfer_data was
        // set at PaymentIntent creation.
        PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
        // Clé d'idempotence stable : un AFTER_COMMIT rejoué ou une redelivery de webhook
        // ne déclenche pas une seconde capture côté Stripe.
        pi.capture(PaymentIntentCaptureParams.builder().build(),
                RequestOptions.builder()
                        .setIdempotencyKey("capture-" + payment.getId())
                        .build());
    }

    private void releaseV2(PaymentEntity payment, DeliveryConfirmedEvent event) throws StripeException {
        // New separate-charges-and-transfers model: PI was already captured on the
        // platform balance at acceptation (BidAcceptedEventListener). Initiate a
        // Transfer to the traveler's Connect account.
        UserEntity traveler = userRepository.findById(event.getTravelerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Traveler not found: " + event.getTravelerId()));

        if (traveler.getStripeAccountId() == null || traveler.getStripeAccountId().isBlank()) {
            throw new IllegalStateException(
                    "Traveler " + traveler.getId() + " has no Stripe Connect account");
        }

        // TODO Q6 (spec bid-checkout-payment-first): si Stripe support révèle des frais
        // de Transfer non-nuls pour les comptes Connect en zone CFA, ajuster le calcul :
        //     net = total - commission - transferFees
        // Pour l'instant on assume Transfers EUR gratuits (zone SEPA / hypothèse MVP).
        BigDecimal net = payment.getAmount().subtract(payment.getCommissionAmount());
        SupportedCurrency currency = SupportedCurrency.fromCode(payment.getCurrency());
        if (currency == null) {
            currency = SupportedCurrency.EUR;
        }
        long netMinor = CurrencyAmount.of(net, currency).minor();

        TransferCreateParams.Builder builder = TransferCreateParams.builder()
                .setAmount(netMinor)
                .setCurrency(currency.code())
                .setDestination(traveler.getStripeAccountId())
                .putMetadata("bid_id", event.getBidId().toString())
                .putMetadata("payment_id", payment.getId() != null ? payment.getId().toString() : "");

        if (payment.getStripeChargeId() != null && !payment.getStripeChargeId().isBlank()) {
            builder.setSourceTransaction(payment.getStripeChargeId());
        }

        // Clé d'idempotence stable : un AFTER_COMMIT rejoué ou une redelivery de webhook
        // ne déclenche pas un second Transfer côté Stripe.
        Transfer.create(builder.build(),
                RequestOptions.builder()
                        .setIdempotencyKey("transfer-" + payment.getId())
                        .build());
    }
}
