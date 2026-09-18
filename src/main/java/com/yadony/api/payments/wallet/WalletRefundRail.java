package com.yadony.api.payments.wallet;

import java.util.UUID;

/**
 * Rail de paiement d'origine d'une recharge, dérivé du {@code paymentRef} stocké sur la
 * transaction {@code TOP_UP} : un PaymentIntent Stripe classique (ex. {@code pi_...}), ou un
 * dépôt pawaPay identifié par le préfixe {@code pawapay:} suivi de l'UUID de l'opération
 * {@code pawapay_operations} (cf. lot 1, recharge mobile money).
 */
public enum WalletRefundRail {
    STRIPE,
    PAWAPAY;

    private static final String PAWAPAY_PREFIX = "pawapay:";

    public static WalletRefundRail of(String paymentRef) {
        if (paymentRef != null && paymentRef.startsWith(PAWAPAY_PREFIX)) {
            return PAWAPAY;
        }
        return STRIPE;
    }

    /** UUID du dépôt {@code pawapay_operations} encodé dans un {@code paymentRef} pawaPay. */
    public static UUID depositId(String paymentRef) {
        if (paymentRef == null || !paymentRef.startsWith(PAWAPAY_PREFIX)) {
            throw new IllegalArgumentException("paymentRef n'est pas un rail pawaPay : " + paymentRef);
        }
        return UUID.fromString(paymentRef.substring(PAWAPAY_PREFIX.length()));
    }
}
