package com.yadony.api.payments.amendment;

import com.stripe.exception.StripeException;
import com.stripe.param.PaymentIntentCreateParams;
import com.yadony.api.payments.supplement.StripeSupplementGateway.ChargeView;
import com.yadony.api.payments.supplement.StripeSupplementGateway.PaymentIntentView;

import java.util.Map;
import java.util.Optional;

/**
 * Appels Stripe du rail carte d'une modification, isolés pour être simulés en test : mêmes méthodes que
 * {@code StripeSupplementGateway} (et mêmes vues), plus la capture de la PaymentIntent en capture manuelle. Chaque
 * création porte une clé d'idempotence ; chaque reprise commence par chercher l'objet déjà créé (métadonnée
 * {@code bid_amendment_id}, {@code transfer_group}), car une clé d'idempotence Stripe expire après 24 h.
 */
public interface StripeAmendmentGateway {

    PaymentIntentView createPaymentIntent(PaymentIntentCreateParams params, String idempotencyKey)
            throws StripeException;

    PaymentIntentView retrievePaymentIntent(String paymentIntentId) throws StripeException;

    /** Métadonnées du PaymentIntent (vide si aucune). */
    Map<String, String> retrieveMetadata(String paymentIntentId) throws StripeException;

    /**
     * Annule le PaymentIntent (autorisation rendue). Déjà annulé : rendu tel quel, sans erreur. Lève une
     * {@link StripeException} s'il a été capturé entre-temps.
     */
    PaymentIntentView cancelPaymentIntent(String paymentIntentId) throws StripeException;

    /**
     * Capture intégrale du PaymentIntent autorisé ({@code requires_capture}). Lève une {@link StripeException} si
     * l'autorisation n'est plus capturable (expirée, annulée : {@code payment_intent_unexpected_state}).
     */
    PaymentIntentView capturePaymentIntent(String paymentIntentId, String idempotencyKey) throws StripeException;

    /** Transfer non annulé du groupe, s'il existe. */
    Optional<String> findTransferByGroup(String transferGroup) throws StripeException;

    String createTransfer(long amountMinor, String currency, String destination, String sourceTransaction,
                          String transferGroup, Map<String, String> metadata, String idempotencyKey)
            throws StripeException;

    /** Remboursement non échoué de ce PaymentIntent portant {@code bid_amendment_id = amendmentId}. */
    Optional<String> findRefund(String paymentIntentId, String amendmentId) throws StripeException;

    /** Remboursement intégral du PaymentIntent. */
    String createRefund(String paymentIntentId, Map<String, String> metadata, String idempotencyKey)
            throws StripeException;

    /** Dernier remboursement non échoué du PaymentIntent (quel qu'en soit l'auteur, Dashboard compris). */
    Optional<String> latestRefund(String paymentIntentId) throws StripeException;

    /** État de la charge : litige bancaire, montant déjà remboursé. */
    ChargeView retrieveCharge(String chargeId) throws StripeException;
}
