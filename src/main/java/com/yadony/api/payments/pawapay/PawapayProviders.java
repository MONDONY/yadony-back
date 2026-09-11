package com.yadony.api.payments.pawapay;

import java.util.Locale;

/** Libellés lisibles des codes opérateur pawaPay ({@code ORANGE_SEN} → « Orange Money »). */
public final class PawapayProviders {

    /** Autorisation par redirection (Wave SN/CI) : l'URL arrive par callback, pas par PIN. */
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

    /**
     * Marque d'un code opérateur : le préfixe avant le premier {@code _} ({@code ORANGE_SEN} et
     * {@code ORANGE_CIV} → {@code ORANGE}). C'est par marque, jamais par code pays, que le rail
     * compare les réseaux du payeur à ceux acceptés par le voyageur : un payeur sénégalais Orange
     * peut payer un voyageur ivoirien Orange. {@code null} si le code est nul ou blanc.
     */
    public static String brand(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        String p = provider.trim().toUpperCase(Locale.ROOT);
        int i = p.indexOf('_');
        return i < 0 ? p : p.substring(0, i);
    }
}
