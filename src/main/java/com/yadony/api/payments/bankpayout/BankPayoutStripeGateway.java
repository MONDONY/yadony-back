package com.yadony.api.payments.bankpayout;

import com.stripe.exception.StripeException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Lectures Stripe du suivi de virement bancaire, isolées pour les tests. Toutes, sauf la lecture
 * du Transfer (objet de la plateforme), se font sur le compte connecté (en-tête
 * {@code Stripe-Account}).
 */
public interface BankPayoutStripeGateway {

    /** Transfer de la plateforme : compte de destination et {@code destination_payment} (py_…). */
    TransferInfo retrieveTransfer(String transferId) throws StripeException;

    /**
     * Date de disponibilité ({@code balance_transaction.available_on}, UTC) du py_… sur le compte
     * connecté. Vide si Stripe ne la donne pas.
     */
    Optional<LocalDate> availableOn(String connectedAccountId, String destinationPaymentId) throws StripeException;

    /** Balance transactions d'un payout automatique du compte connecté (toutes pages). */
    List<BalanceTxnRef> payoutBalanceTransactions(String connectedAccountId, String payoutId) throws StripeException;

    /** Payouts du compte connecté créés depuis {@code createdGteEpoch} (toutes pages). */
    List<PayoutSnapshot> recentPayouts(String connectedAccountId, long createdGteEpoch) throws StripeException;

    record TransferInfo(String id, String destination, String destinationPayment) {}

    /** {@code type} Stripe ({@code payment}, {@code payout_failure}…) et {@code source} (py_…, po_…). */
    record BalanceTxnRef(String type, String source) {}
}
