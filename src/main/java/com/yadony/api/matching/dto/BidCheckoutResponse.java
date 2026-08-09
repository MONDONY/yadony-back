package com.yadony.api.matching.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record BidCheckoutResponse(
        UUID bidId,
        String clientSecret,
        String publishableKey,
        LocalDateTime expiresAt,
        String currency,
        // Types du PaymentIntent (ex. ["card","paypal"]) — bouton PayPal conditionnel
        // dans la YadonyPaymentSheet (le SDK flutter_stripe ne les expose pas).
        List<String> paymentMethodTypes
) {
    /** Constructeur historique (sans paymentMethodTypes). */
    public BidCheckoutResponse(UUID bidId, String clientSecret, String publishableKey,
                               LocalDateTime expiresAt) {
        this(bidId, clientSecret, publishableKey, expiresAt, "eur", null);
    }

    /** Constructeur historique avec les moyens de paiement, sans devise. */
    public BidCheckoutResponse(UUID bidId, String clientSecret, String publishableKey,
                               LocalDateTime expiresAt, List<String> paymentMethodTypes) {
        this(bidId, clientSecret, publishableKey, expiresAt, "eur", paymentMethodTypes);
    }
}
