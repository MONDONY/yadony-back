package com.yadony.api.payments.split;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Implémentation 1:1 sur le SDK Stripe. */
@Component
public class StripeSplitGatewayImpl implements StripeSplitGateway {

    @Override
    public PaymentIntentState retrievePaymentIntent(String paymentIntentId) throws StripeException {
        return state(PaymentIntent.retrieve(paymentIntentId));
    }

    @Override
    public Optional<String> findRefund(String paymentIntentId, String splitId) throws StripeException {
        return Refund.list(RefundListParams.builder().setPaymentIntent(paymentIntentId).setLimit(100L).build())
                .getData().stream()
                .filter(r -> r.getMetadata() != null && splitId.equals(r.getMetadata().get("split_id")))
                .filter(r -> !"failed".equals(r.getStatus()) && !"canceled".equals(r.getStatus()))
                .map(Refund::getId)
                .findFirst();
    }

    @Override
    public String createRefund(String paymentIntentId, long amountMinor, Map<String, String> metadata,
                               String idempotencyKey) throws StripeException {
        return Refund.create(RefundCreateParams.builder()
                        .setPaymentIntent(paymentIntentId)
                        .setAmount(amountMinor)
                        .putAllMetadata(metadata)
                        .build(),
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()).getId();
    }

    @Override
    public PaymentIntentState capture(String paymentIntentId, long amountToCaptureMinor, String idempotencyKey)
            throws StripeException {
        PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId);
        return state(pi.capture(PaymentIntentCaptureParams.builder().setAmountToCapture(amountToCaptureMinor).build(),
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()));
    }

    @Override
    public Optional<String> findTransfer(String transferGroup) throws StripeException {
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
        return Transfer.create(builder.build(),
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()).getId();
    }

    private static PaymentIntentState state(PaymentIntent pi) {
        return new PaymentIntentState(pi.getStatus(), pi.getAmount(), pi.getAmountReceived(), pi.getLatestCharge());
    }
}
