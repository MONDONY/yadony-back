package com.yadony.api.payments.reconciliation;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;

import java.time.Instant;
import java.util.List;

/** Lectures Stripe du rapprochement, isolées pour être simulées en test. */
public interface StripeLedgerSource {

    /** Le PaymentIntent avec sa dernière charge dépliée ({@code latest_charge}), pour le remboursé. */
    PaymentIntent retrieveWithLatestCharge(String paymentIntentId) throws StripeException;

    /** Les PaymentIntent de recharge wallet ({@code metadata.wallet_topup = true}) créés depuis {@code since}. */
    List<PaymentIntent> walletTopupsCreatedSince(Instant since) throws StripeException;
}
