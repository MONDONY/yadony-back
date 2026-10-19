package com.yadony.api.payments.supplement;

import com.stripe.exception.StripeException;
import com.stripe.param.PaymentIntentCreateParams;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Appels Stripe du supplément payé par carte, isolés pour être mockés en test (modèle
 * {@code StripeSplitGateway}). Chaque création porte une clé d'idempotence ; chaque reprise
 * commence par chercher l'objet déjà créé ({@code transfer_group} ou métadonnée
 * {@code bid_supplement_id}), car une clé d'idempotence Stripe expire après 24 h.
 */
public interface StripeSupplementGateway {

    PaymentIntentView createPaymentIntent(PaymentIntentCreateParams params, String idempotencyKey)
            throws StripeException;

    PaymentIntentView retrievePaymentIntent(String paymentIntentId) throws StripeException;

    /** Annule le PaymentIntent ; lève une {@link StripeException} s'il a déjà abouti. */
    PaymentIntentView cancelPaymentIntent(String paymentIntentId) throws StripeException;

    /** Transfer non annulé du groupe, s'il existe. */
    Optional<String> findTransferByGroup(String transferGroup) throws StripeException;

    String createTransfer(long amountMinor, String currency, String destination, String sourceTransaction,
                          String transferGroup, Map<String, String> metadata, String idempotencyKey)
            throws StripeException;

    /** Remboursement non échoué de ce PaymentIntent portant {@code bid_supplement_id = supplementId}. */
    Optional<String> findRefund(String paymentIntentId, String supplementId) throws StripeException;

    /** Remboursement intégral du PaymentIntent. */
    String createRefund(String paymentIntentId, Map<String, String> metadata, String idempotencyKey)
            throws StripeException;

    /** Dernier remboursement non échoué du PaymentIntent (quel qu'en soit l'auteur, Dashboard compris). */
    Optional<String> latestRefund(String paymentIntentId) throws StripeException;

    /** État de la charge du supplément : litige bancaire, montant déjà remboursé. */
    ChargeView retrieveCharge(String chargeId) throws StripeException;

    /** Vue minimale d'une charge. */
    record ChargeView(boolean disputed, long amountRefunded) {
    }

    /** Vue minimale d'un PaymentIntent. */
    record PaymentIntentView(String id, String status, Long amount, String currency, String clientSecret,
                             String latestCharge, List<String> paymentMethodTypes) {

        public boolean succeeded() {
            return "succeeded".equals(status);
        }

        public boolean canceled() {
            return "canceled".equals(status);
        }

        /** Encore payable dans la PaymentSheet : on la réutilise plutôt que d'en créer une autre. */
        public boolean payable() {
            return "requires_payment_method".equals(status) || "requires_confirmation".equals(status)
                    || "requires_action".equals(status);
        }
    }
}
