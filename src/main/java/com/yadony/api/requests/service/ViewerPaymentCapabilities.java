package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import java.util.List;

/**
 * Capacités de paiement du visiteur d'une demande (un voyageur, ou personne) : ce qu'il
 * pourrait fournir s'il liait son trajet. Un instantané de UserEntity, calculé une fois par
 * requête HTTP et appliqué à chaque demande d'une page : les devises de ses comptes de versement
 * mobile money sont gardées, pas un booléen, parce que la disponibilité se décide demande par
 * demande selon SA devise ({@link UserEntity#canReceiveMobileMoney}). Un voyageur qui a un compte
 * XOF et un compte XAF peut recevoir sur une demande XAF.
 */
public record ViewerPaymentCapabilities(boolean hasConnect, List<String> mobileMoneyCurrencies) {

    /** Visiteur anonyme ou inconnu : rien d'autre que l'espèce. */
    public static final ViewerPaymentCapabilities NONE = new ViewerPaymentCapabilities(false, List.of());

    public ViewerPaymentCapabilities {
        mobileMoneyCurrencies = mobileMoneyCurrencies == null ? List.of() : List.copyOf(mobileMoneyCurrencies);
    }

    public static ViewerPaymentCapabilities of(UserEntity user) {
        if (user == null) {
            return NONE;
        }
        return new ViewerPaymentCapabilities(
            user.hasActiveStripeConnect(),
            user.hasActiveMobileMoney() ? user.getMobileMoneyCurrencyList() : List.of());
    }

    /** Vrai si un compte de versement mobile money est actif dans {@code currency} (casse ignorée). */
    public boolean canReceiveMobileMoney(String currency) {
        return currency != null && mobileMoneyCurrencies.stream().anyMatch(currency::equalsIgnoreCase);
    }
}
