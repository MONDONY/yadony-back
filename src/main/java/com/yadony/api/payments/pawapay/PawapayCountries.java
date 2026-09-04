package com.yadony.api.payments.pawapay;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** pawaPay parle ISO 3166-1 alpha-3 (SEN, CIV…), yadony alpha-2 (SN, CI…). Table dérivée du JDK. */
public final class PawapayCountries {

    private static final Map<String, String> ALPHA3_TO_2 = new HashMap<>();
    private static final Map<String, String> ALPHA2_TO_3 = new HashMap<>();

    static {
        for (String a2 : Locale.getISOCountries()) {
            String a3 = new Locale("", a2).getISO3Country();
            ALPHA3_TO_2.put(a3, a2);
            ALPHA2_TO_3.put(a2, a3);
        }
    }

    private PawapayCountries() {}

    public static String toAlpha2(String alpha3) {
        return alpha3 == null ? null : ALPHA3_TO_2.get(alpha3.trim().toUpperCase(Locale.ROOT));
    }

    public static String toAlpha3(String alpha2) {
        return alpha2 == null ? null : ALPHA2_TO_3.get(alpha2.trim().toUpperCase(Locale.ROOT));
    }
}
