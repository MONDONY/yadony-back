package com.yadony.api.payments;

import java.util.Locale;
import java.util.Set;

/**
 * Pays dans lesquels Stripe accepte d'ouvrir un compte connecté « recipient »
 * (capacité {@code stripe_balance.stripe_transfers}).
 *
 * <p>{@code CountryCatalog} décrit les 38 pays desservis par yadony ; Stripe n'en
 * couvre que 22. Pour les 16 autres (US, CA, zone XOF, zone XAF), le paiement par
 * carte est simplement indisponible et le voyageur reste en espèces. Ce n'est pas
 * une conséquence de la migration Accounts v2 : l'API v1 refusait exactement les
 * mêmes pays, soit comme non desservis, soit en exigeant {@code card_payments} en
 * plus de {@code transfers}.
 *
 * <p>Cette liste double une contrainte qui vit chez Stripe. Elle sert uniquement à
 * rendre un message lisible <em>avant</em> l'appel réseau et à masquer une action
 * impossible côté application : un refus de Stripe reste possible et doit rester
 * géré comme tel.
 */
public final class StripeConnectCountries {

    private static final Set<String> SUPPORTED = Set.of(
            // Zone euro / SEPA
            "AT", "BE", "HR", "CY", "EE", "FI", "FR", "DE", "GR", "IE",
            "IT", "LV", "LT", "LU", "MT", "NL", "PT", "SK", "SI", "ES",
            // Hors zone euro
            "CH", "GB");

    private StripeConnectCountries() {
    }

    public static boolean isSupported(String iso2) {
        return iso2 != null && SUPPORTED.contains(iso2.toUpperCase(Locale.ROOT));
    }
}
