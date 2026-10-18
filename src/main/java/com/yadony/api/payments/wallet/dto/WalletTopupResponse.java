package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.ValidationHint;
import java.time.Instant;
import java.util.UUID;

/**
 * Réponse d'initiation d'une recharge, quelle que soit sa voie. Un seul des deux jeux de
 * champs est renseigné : {@code clientSecret} pour Stripe (l'app confirme le PaymentIntent),
 * l'identité de l'opération pawaPay pour le mobile money (l'app poll le statut, et ouvre
 * {@code authorizationUrl} quand l'opérateur autorise par redirection — Wave).
 */
public class WalletTopupResponse {

    private final String clientSecret;     // STRIPE
    private final String redirectUrl;      // hérité, toujours null
    private final UUID topupId;            // MOBILE_MONEY
    private final String currency;
    private final String provider;
    private final String providerLabel;
    private final String msisdnMasked;
    private final String authorizationUrl; // Wave : page à ouvrir
    private final Instant expiresAt;       // MOBILE_MONEY : fin du délai de validation
    private final ValidationHint validationHint; // MOBILE_MONEY : comment l'opérateur demande la validation

    private WalletTopupResponse(String clientSecret, String redirectUrl, UUID topupId, String currency,
                                String provider, String providerLabel, String msisdnMasked,
                                String authorizationUrl, Instant expiresAt, ValidationHint validationHint) {
        this.clientSecret = clientSecret;
        this.redirectUrl = redirectUrl;
        this.topupId = topupId;
        this.currency = currency;
        this.provider = provider;
        this.providerLabel = providerLabel;
        this.msisdnMasked = msisdnMasked;
        this.authorizationUrl = authorizationUrl;
        this.expiresAt = expiresAt;
        this.validationHint = validationHint;
    }

    public static WalletTopupResponse stripe(String clientSecret) {
        return new WalletTopupResponse(clientSecret, null, null, null, null, null, null, null, null, null);
    }

    public static WalletTopupResponse mobileMoney(PawapayOperationEntity op, Instant expiresAt,
                                                  ValidationHint validationHint) {
        return new WalletTopupResponse(null, null, op.getId(), op.getCurrency(), op.getProvider(),
                PawapayProviders.label(op.getProvider()), op.getMsisdnMasked(), op.getAuthorizationUrl(),
                expiresAt, validationHint);
    }

    public String getClientSecret() { return clientSecret; }
    public String getRedirectUrl() { return redirectUrl; }
    public UUID getTopupId() { return topupId; }
    public String getCurrency() { return currency; }
    public String getProvider() { return provider; }
    public String getProviderLabel() { return providerLabel; }
    public String getMsisdnMasked() { return msisdnMasked; }
    public String getAuthorizationUrl() { return authorizationUrl; }
    public Instant getExpiresAt() { return expiresAt; }
    public ValidationHint getValidationHint() { return validationHint; }
}
