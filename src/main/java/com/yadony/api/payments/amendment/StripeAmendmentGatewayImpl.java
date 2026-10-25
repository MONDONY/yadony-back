package com.yadony.api.payments.amendment;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.yadony.api.payments.supplement.StripeSupplementGateway.ChargeView;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Implémentation 1:1 sur le SDK Stripe. */
@Component
public class StripeAmendmentGatewayImpl implements StripeAmendmentGateway {

    static final String METADATA_KEY = "bid_amendment_id";

    @Override
    public PaymentIntentView createPaymentIntent(PaymentIntentCreateParams params, String idempotencyKey)
            throws StripeException {
        return view(PaymentIntent.create(params, key(idempotencyKey)));
    }

    @Override
    public PaymentIntentView retrievePaymentIntent(String paymentIntentId) throws StripeException {
        return view(PaymentIntent.retrieve(paymentIntentId));
    }

    @Override
    public Map<String, String> retrieveMetadata(String paymentIntentId) throws StripeException {
        Map<String, String> metadata = PaymentIntent.retrieve(paymentIntentId).getMetadata();
        return metadata == null ? Map.of() : metadata;
    }

    @Override
    public PaymentIntentView cancelPaymentIntent(String paymentIntentId) throws StripeException {
        PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
        if ("canceled".equals(pi.getStatus())) {
            return view(pi);
        }
        return view(pi.cancel(PaymentIntentCancelParams.builder()
                .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                .build()));
    }

    @Override
    public PaymentIntentView capturePaymentIntent(String paymentIntentId, String idempotencyKey)
            throws StripeException {
        PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
        return view(pi.capture(PaymentIntentCaptureParams.builder().build(), key(idempotencyKey)));
    }

    @Override
    public Optional<String> findTransferByGroup(String transferGroup) throws StripeException {
        return Transfer.list(TransferListParams.builder().setTransferGroup(transferGroup).setLimit(10L).build())
                .getData().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getReversed()))
                .map(Transfer::getId)
                .findFirst();
    }

    @Override
    public String createTransfer(long amountMinor, String currency, String destination, String sourceTransaction,
                                 String transferGroup, Map<String, String> metadata, String idempotencyKey)
            throws StripeException {
        TransferCreateParams.Builder builder = TransferCreateParams.builder()
                .setAmount(amountMinor)
                .setCurrency(currency)
                .setDestination(destination)
                .setTransferGroup(transferGroup)
                .putAllMetadata(metadata);
        if (sourceTransaction != null && !sourceTransaction.isBlank()) {
            builder.setSourceTransaction(sourceTransaction);
        }
        return Transfer.create(builder.build(), key(idempotencyKey)).getId();
    }

    @Override
    public Optional<String> findRefund(String paymentIntentId, String amendmentId) throws StripeException {
        return Refund.list(RefundListParams.builder().setPaymentIntent(paymentIntentId).setLimit(100L).build())
                .getData().stream()
                .filter(r -> r.getMetadata() != null && amendmentId.equals(r.getMetadata().get(METADATA_KEY)))
                .filter(r -> !"failed".equals(r.getStatus()) && !"canceled".equals(r.getStatus()))
                .map(Refund::getId)
                .findFirst();
    }

    @Override
    public String createRefund(String paymentIntentId, Map<String, String> metadata, String idempotencyKey)
            throws StripeException {
        return Refund.create(RefundCreateParams.builder()
                        .setPaymentIntent(paymentIntentId)
                        .putAllMetadata(metadata)
                        .build(),
                key(idempotencyKey)).getId();
    }

    @Override
    public Optional<String> latestRefund(String paymentIntentId) throws StripeException {
        // Stripe liste du plus récent au plus ancien.
        return Refund.list(RefundListParams.builder().setPaymentIntent(paymentIntentId).setLimit(10L).build())
                .getData().stream()
                .filter(r -> !"failed".equals(r.getStatus()) && !"canceled".equals(r.getStatus()))
                .map(Refund::getId)
                .findFirst();
    }

    @Override
    public ChargeView retrieveCharge(String chargeId) throws StripeException {
        Charge charge = Charge.retrieve(chargeId);
        return new ChargeView(Boolean.TRUE.equals(charge.getDisputed()),
                charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded());
    }

    private static RequestOptions key(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static PaymentIntentView view(PaymentIntent pi) {
        return new PaymentIntentView(pi.getId(), pi.getStatus(), pi.getAmount(), pi.getCurrency(),
                pi.getClientSecret(), pi.getLatestCharge(), pi.getPaymentMethodTypes());
    }
}
