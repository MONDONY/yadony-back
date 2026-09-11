package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;

/**
 * Capacités de paiement du visiteur d'une demande (un voyageur, ou personne) : ce qu'il
 * pourrait fournir s'il liait son trajet. Un instantané de deux champs de UserEntity, calculé
 * une fois par requête HTTP et appliqué à chaque demande d'une page : la devise du compte
 * mobile money est gardée, pas un booléen, parce que la disponibilité se décide demande par
 * demande selon SA devise ({@link UserEntity#canReceiveMobileMoney}).
 */
public record ViewerPaymentCapabilities(boolean hasConnect, String mobileMoneyCurrency) {

    /** Visiteur anonyme ou inconnu : rien d'autre que l'espèce. */
    public static final ViewerPaymentCapabilities NONE = new ViewerPaymentCapabilities(false, null);

    public static ViewerPaymentCapabilities of(UserEntity user) {
        if (user == null) {
            return NONE;
        }
        return new ViewerPaymentCapabilities(
            user.hasActiveStripeConnect(),
            user.hasActiveMobileMoney() ? user.getMobileMoneyCurrency() : null);
    }

    /** Vrai si le compte de versement mobile money est actif dans {@code currency}. */
    public boolean canReceiveMobileMoney(String currency) {
        return mobileMoneyCurrency != null && currency != null && mobileMoneyCurrency.equalsIgnoreCase(currency);
    }
}
