package com.yadony.api.payments;

import com.yadony.api.common.stripe.StripeWebhookHandler;
import com.yadony.api.payments.cash.CashCommissionWebhookHandler;
import com.yadony.api.payments.chargeback.ChargebackService;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.payments.wallet.WalletTransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class PaymentStripeWebhookHandler implements StripeWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentStripeWebhookHandler.class);

    private static final Set<String> SUPPORTED = Set.of(
            "account.updated",
            "payment_intent.amount_capturable_updated",
            "payment_intent.payment_failed",
            "payment_intent.canceled",
            "charge.refunded",
            "setup_intent.succeeded",
            "payment_intent.succeeded",
            "payment_method.detached",
            "charge.dispute.created",
            "charge.dispute.closed",
            "charge.dispute.funds_withdrawn",
            "charge.dispute.funds_reinstated",
            "transfer.created",
            "transfer.reversed",
            "transfer.updated",
            "payout.failed",
            "payout.paid",
            "account.application.deauthorized",
            "capability.updated",
            "charge.refund.updated",
            "radar.early_fraud_warning.created"
    );

    private final PaymentService paymentService;
    private final CashCommissionWebhookHandler cashHandler;
    private final ChargebackService chargebackService;
    private final WalletService walletService;
    private final ObjectMapper objectMapper;

    public PaymentStripeWebhookHandler(PaymentService paymentService,
                                        CashCommissionWebhookHandler cashHandler,
                                        ChargebackService chargebackService,
                                        WalletService walletService,
                                        ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.cashHandler = cashHandler;
        this.chargebackService = chargebackService;
        this.walletService = walletService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String eventType) {
        return SUPPORTED.contains(eventType);
    }

    @Override
    public void handle(Event event) {
        switch (event.getType()) {
            case "account.updated"                          -> paymentService.handleAccountUpdated(event);
            case "payment_intent.amount_capturable_updated" -> paymentService.handlePaymentEscrowActive(event);
            case "payment_intent.payment_failed" -> {
                paymentService.handlePaymentFailed(event);
                cashHandler.handlePaymentIntentFailed(event);
            }
            case "charge.refunded"                    -> paymentService.handleChargeRefunded(event);
            case "setup_intent.succeeded"             -> cashHandler.handleSetupIntentSucceeded(event);
            case "payment_intent.succeeded" -> {
                PaymentIntent pi = resolvePaymentIntent(event);
                if (pi != null
                        && pi.getMetadata() != null
                        && "true".equals(pi.getMetadata().get("wallet_topup"))) {
                    String rawUserId = pi.getMetadata().get("user_id");
                    if (rawUserId == null) {
                        log.warn("wallet_topup webhook sans user_id, event ignoré: {}", pi.getId());
                        return;
                    }
                    UUID userId = UUID.fromString(rawUserId);
                    String walletCreditEur = pi.getMetadata().get("wallet_credit_eur");
                    BigDecimal amount = walletCreditEur != null
                        ? new BigDecimal(walletCreditEur)
                        : BigDecimal.valueOf(pi.getAmount())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    walletService.credit(userId, "EUR", amount, WalletTransactionType.TOP_UP,
                        pi.getId(), "stripe-" + pi.getId());
                } else {
                    cashHandler.handlePaymentIntentSucceeded(event);
                }
            }
            case "payment_method.detached"            -> cashHandler.handlePaymentMethodDetached(event);
            case "charge.dispute.created"             -> chargebackService.handleDisputeCreated(event);
            case "charge.dispute.closed"              -> chargebackService.handleDisputeClosed(event);
            case "charge.dispute.funds_withdrawn"     -> chargebackService.handleFundsWithdrawn(event);
            case "charge.dispute.funds_reinstated"    -> chargebackService.handleFundsReinstated(event);
            case "payment_intent.canceled"             -> paymentService.handlePaymentIntentCanceled(event);
            case "transfer.created"                    -> paymentService.handleTransferCreated(event);
            case "transfer.reversed"                   -> paymentService.handleTransferReversed(event);
            case "transfer.updated"                    -> paymentService.handleTransferUpdated(event);
            case "payout.failed"                       -> paymentService.handlePayoutFailed(event);
            case "payout.paid"                         -> paymentService.handlePayoutPaid(event);
            case "account.application.deauthorized"   -> paymentService.handleAccountDeauthorized(event);
            case "capability.updated"                 -> paymentService.handleCapabilityUpdated(event);
            case "charge.refund.updated"              -> paymentService.handleRefundUpdated(event);
            case "radar.early_fraud_warning.created"  -> paymentService.handleEarlyFraudWarning(event);
        }
    }

    /**
     * Résout le PaymentIntent d'un event. Si le deserializer est vide — mismatch
     * de version d'API entre Stripe (serveur/CLI) et le SDK Java — on récupère le
     * PI via l'API live. Même pattern que {@code PaymentService.handlePaymentEscrowActive}.
     * Sans ce fallback, une recharge wallet (capture_method=automatic →
     * payment_intent.succeeded) n'est jamais créditée car {@code getObject()} renvoie
     * {@code Optional.empty()} et le routage tombe dans la branche bid.
     */
    private PaymentIntent resolvePaymentIntent(Event event) {
        Optional<StripeObject> objOpt = event.getDataObjectDeserializer().getObject();
        if (objOpt.isPresent()) {
            return (PaymentIntent) objOpt.get();
        }
        try {
            String rawJson = event.getDataObjectDeserializer().getRawJson();
            JsonNode node = objectMapper.readTree(rawJson);
            String piId = node.get("id").asText();
            log.debug("payment_intent.succeeded: deserializer vide pour event {}, fetch PI {} via API",
                    event.getId(), piId);
            return PaymentIntent.retrieve(piId);
        } catch (Exception e) {
            log.error("payment_intent.succeeded: impossible de résoudre le PaymentIntent depuis l'event {}: {}",
                    event.getId(), e.getMessage(), e);
            return null;
        }
    }
}
