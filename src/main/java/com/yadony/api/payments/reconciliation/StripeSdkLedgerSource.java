package com.yadony.api.payments.reconciliation;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentListParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** {@link StripeLedgerSource} sur le SDK Stripe, comme {@code StripeGatewayImpl}. */
@Component
public class StripeSdkLedgerSource implements StripeLedgerSource {

    @Override
    public PaymentIntent retrieveWithLatestCharge(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId,
                PaymentIntentRetrieveParams.builder().addExpand("latest_charge").build(), null);
    }

    @Override
    public List<PaymentIntent> walletTopupsCreatedSince(Instant since) throws StripeException {
        PaymentIntentListParams params = PaymentIntentListParams.builder()
                .setCreated(PaymentIntentListParams.Created.builder().setGte(since.getEpochSecond()).build())
                .setLimit(100L)
                .build();
        List<PaymentIntent> topups = new ArrayList<>();
        for (PaymentIntent pi : PaymentIntent.list(params).autoPagingIterable()) {
            if (pi.getMetadata() != null && "true".equals(pi.getMetadata().get("wallet_topup"))) {
                topups.add(pi);
            }
        }
        return topups;
    }
}
