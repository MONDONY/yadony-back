package com.yadony.api.payments.pawapay;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** pawaPay parle ISO 3166-1 alpha-3 (SEN, CIV…), yadony alpha-2 (SN, CI…). Table dérivée du JDK. */
public final class PawapayCountries {

    private static final Map<String, String> ALPHA3_TO_2 = new HashMap<>();

    static {
        for (String a2 : Locale.getISOCountries()) {
            ALPHA3_TO_2.put(new Locale("", a2).getISO3Country(), a2);
        }
    }

    private PawapayCountries() {}

    /** {@code null} si l'alpha-3 est absent de la table ISO du JDK — à garder par l'appelant, jamais accepté tel quel. */
    public static String toAlpha2(String alpha3) {
        return alpha3 == null ? null : ALPHA3_TO_2.get(alpha3.trim().toUpperCase(Locale.ROOT));
    }
}
