package com.yadony.api.payments.bankpayout;

import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.BalanceTransactionCollection;
import com.stripe.model.Charge;
import com.stripe.model.Payout;
import com.stripe.model.PayoutCollection;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.BalanceTransactionListParams;
import com.stripe.param.ChargeRetrieveParams;
import com.stripe.param.PayoutListParams;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Appels SDK 1:1 ; les lectures du compte connecté passent l'en-tête {@code Stripe-Account}. */
@Component
public class BankPayoutStripeGatewayImpl implements BankPayoutStripeGateway {

    @Override
    public TransferInfo retrieveTransfer(String transferId) throws StripeException {
        Transfer t = Transfer.retrieve(transferId);
        return new TransferInfo(t.getId(), t.getDestination(), t.getDestinationPayment());
    }

    @Override
    public Optional<LocalDate> availableOn(String connectedAccountId, String destinationPaymentId)
            throws StripeException {
        Charge py = Charge.retrieve(destinationPaymentId,
                ChargeRetrieveParams.builder().addExpand("balance_transaction").build(),
                onAccount(connectedAccountId));
        BalanceTransaction bt = py != null ? py.getBalanceTransactionObject() : null;
        return bt == null || bt.getAvailableOn() == null ? Optional.empty() : Optional.of(epochDay(bt.getAvailableOn()));
    }

    @Override
    public List<BalanceTxnRef> payoutBalanceTransactions(String connectedAccountId, String payoutId)
            throws StripeException {
        BalanceTransactionListParams params = BalanceTransactionListParams.builder()
                .setPayout(payoutId).setLimit(100L).build();
        RequestOptions options = onAccount(connectedAccountId);
        BalanceTransactionCollection page = BalanceTransaction.list(params, options);
        List<BalanceTxnRef> refs = new ArrayList<>();
        for (BalanceTransaction bt : page.autoPagingIterable()) {
            refs.add(new BalanceTxnRef(bt.getType(), bt.getSource()));
        }
        return refs;
    }

    @Override
    public List<PayoutSnapshot> recentPayouts(String connectedAccountId, long createdGteEpoch)
            throws StripeException {
        PayoutListParams params = PayoutListParams.builder()
                .setCreated(PayoutListParams.Created.builder().setGte(createdGteEpoch).build())
                .setLimit(100L).build();
        RequestOptions options = onAccount(connectedAccountId);
        PayoutCollection page = Payout.list(params, options);
        List<PayoutSnapshot> payouts = new ArrayList<>();
        for (Payout p : page.autoPagingIterable()) {
            payouts.add(new PayoutSnapshot(p.getId(), p.getStatus(),
                    p.getArrivalDate() == null ? null : epochDay(p.getArrivalDate()),
                    p.getAmount(), p.getCurrency(), p.getFailureCode(), p.getAutomatic(), p.getCreated()));
        }
        return payouts;
    }

    private static RequestOptions onAccount(String connectedAccountId) {
        return RequestOptions.builder().setStripeAccount(connectedAccountId).build();
    }

    static LocalDate epochDay(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
