package com.yadony.api.payments.split;

import com.stripe.exception.StripeException;

import java.util.Map;
import java.util.Optional;

/**
 * Appels Stripe d'un partage, isolés pour être mockés en test. Chaque création porte une clé
 * d'idempotence ; chaque reprise commence par chercher l'objet déjà créé (métadonnée
 * {@code split_id} ou {@code transfer_group}), car une clé d'idempotence Stripe expire après 24 h.
 */
public interface StripeSplitGateway {

    /** Statut du PaymentIntent ({@code succeeded}, {@code requires_capture}, ...). */
    PaymentIntentState retrievePaymentIntent(String paymentIntentId) throws StripeException;

    Optional<String> findRefund(String paymentIntentId, String splitId) throws StripeException;

    String createRefund(String paymentIntentId, long amountMinor, Map<String, String> metadata,
                        String idempotencyKey) throws StripeException;

    /** Capture partielle d'un PaymentIntent autorisé ; renvoie l'état après capture. */
    PaymentIntentState capture(String paymentIntentId, long amountToCaptureMinor, String idempotencyKey)
            throws StripeException;

    Optional<String> findTransfer(String transferGroup) throws StripeException;

    String createTransfer(long amountMinor, String currency, String destination, String sourceTransaction,
                          String transferGroup, Map<String, String> metadata, String idempotencyKey)
            throws StripeException;

    /** Vue minimale d'un PaymentIntent. */
    record PaymentIntentState(String status, Long amount, Long amountReceived, String latestCharge) {
    }
}
