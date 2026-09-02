package com.yadony.api.payments;

import java.util.Locale;
import java.util.Set;

/**
 * Pays dans lesquels un voyageur peut ouvrir un compte Stripe Connect.
 *
 * <p>Deux familles, deux formes de compte :
 * <ul>
 *   <li><b>Recipient seul</b> (zone euro/SEPA, CH, GB) : configuration {@code recipient}
 *       avec {@code stripe_balance.stripe_transfers} — onboarding réduit (identité + IBAN),
 *       aucune vérification marchande.</li>
 *   <li><b>Merchant + recipient</b> (US, CA) : Stripe refuse {@code stripe_transfers} sans
 *       {@code merchant.card_payments} dans ces pays
 *       ({@code capability_not_available_without_other_capability}, vérifié en test mode le
 *       2026-09-03). La création passe alors par un account token v2
 *       ({@code account_token_required} pour une plateforme française) et l'onboarding
 *       hébergé collecte la vérification marchande (SSN aux US, activité…).</li>
 * </ul>
 *
 * <p>{@code CountryCatalog} décrit les 38 pays desservis par yadony ; les pays restants
 * (zone XOF, zone XAF…) ne sont pas couverts par Stripe : paiement par carte indisponible,
 * le voyageur reste en espèces.
 *
 * <p>Cette liste double une contrainte qui vit chez Stripe. Elle sert uniquement à rendre
 * un message lisible <em>avant</em> l'appel réseau et à masquer une action impossible côté
 * application : un refus de Stripe reste possible et doit rester géré comme tel.
 */
public final class StripeConnectCountries {

    private static final Set<String> RECIPIENT_ONLY = Set.of(
            // Zone euro / SEPA
            "AT", "BE", "HR", "CY", "EE", "FI", "FR", "DE", "GR", "IE",
            "IT", "LV", "LT", "LU", "MT", "NL", "PT", "SK", "SI", "ES",
            // Hors zone euro
            "CH", "GB");

    /**
     * Pays où Stripe exige la configuration {@code merchant} (card_payments) en plus du
     * {@code recipient} — création par account token, onboarding marchand complet.
     */
    private static final Set<String> MERCHANT_REQUIRED = Set.of("US", "CA");

    private StripeConnectCountries() {
    }

    public static boolean isSupported(String iso2) {
        if (iso2 == null) {
            return false;
        }
        String code = iso2.toUpperCase(Locale.ROOT);
        return RECIPIENT_ONLY.contains(code) || MERCHANT_REQUIRED.contains(code);
    }

    /**
     * Vrai pour les pays où le compte doit porter la configuration marchande dès la
     * création (US, CA). Ne jamais demander {@code card_payments} après coup : la greffe
     * marchande sur un compte recipient existant exige {@code mcc} + {@code phone} en
     * {@code past_due} et désactive tout le compte (incident du 2026-09-02).
     */
    public static boolean requiresMerchantConfiguration(String iso2) {
        return iso2 != null && MERCHANT_REQUIRED.contains(iso2.toUpperCase(Locale.ROOT));
    }
}
