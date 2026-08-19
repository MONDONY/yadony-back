package com.yadony.api.payments.currency;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Table pays -> devise, source unique de la derivation. Un pays n'y figure que si
 * le rail de paiement de sa devise est utilisable : les 24 pays EUR/CHF/GBP/CAD/USD
 * sont des pays Stripe Connect valides, les 14 pays de la zone franc CFA ne le sont
 * pas et restent en especes, comme deja porte par {@link CurrencyPaymentRails}.
 */
public final class CountryCatalog {

    private static final Map<String, SupportedCurrency> BY_COUNTRY;

    static {
        Map<String, SupportedCurrency> m = new HashMap<>();
        for (String c : new String[] {
                "AT", "BE", "HR", "CY", "EE", "FI", "FR", "DE", "GR", "IE",
                "IT", "LV", "LT", "LU", "MT", "NL", "PT", "SK", "SI", "ES" }) {
            m.put(c, SupportedCurrency.EUR);
        }
        m.put("CH", SupportedCurrency.CHF);
        m.put("GB", SupportedCurrency.GBP);
        m.put("CA", SupportedCurrency.CAD);
        m.put("US", SupportedCurrency.USD);
        for (String c : new String[] {
                "BJ", "BF", "CI", "GW", "ML", "NE", "SN", "TG" }) {
            m.put(c, SupportedCurrency.XOF);
        }
        for (String c : new String[] {
                "CM", "CF", "TD", "CG", "GQ", "GA" }) {
            m.put(c, SupportedCurrency.XAF);
        }
        BY_COUNTRY = Map.copyOf(m);
    }

    private CountryCatalog() {}

    /** Devise du pays, ou {@code null} si le code est absent, vide ou hors catalogue. */
    public static SupportedCurrency currencyOf(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            return null;
        }
        return BY_COUNTRY.get(iso2.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean isSupported(String iso2) {
        return currencyOf(iso2) != null;
    }

    public static Set<String> all() {
        return Collections.unmodifiableSet(BY_COUNTRY.keySet());
    }
}
