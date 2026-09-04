package com.yadony.api.payments.pawapay;

import java.util.Locale;

/** Libellés lisibles des codes opérateur pawaPay ({@code ORANGE_SEN} → « Orange Money »). */
public final class PawapayProviders {

    public static final String REDIRECT_AUTH = "REDIRECT_AUTH";

    private PawapayProviders() {}

    public static String label(String provider) {
        if (provider == null) {
            return "Mobile money";
        }
        String p = provider.toUpperCase(Locale.ROOT);
        if (p.startsWith("ORANGE")) return "Orange Money";
        if (p.startsWith("WAVE")) return "Wave";
        if (p.startsWith("MTN")) return "MTN MoMo";
        if (p.startsWith("FREE")) return "Free Money";
        if (p.startsWith("MOOV")) return "Moov Money";
        if (p.startsWith("AIRTEL")) return "Airtel Money";
        return provider;
    }

    /** Wave (SN, CI) autorise par redirection : l'URL arrive par callback, pas par PIN. */
    public static boolean isRedirect(String provider, String authType) {
        return REDIRECT_AUTH.equals(authType);
    }
}
