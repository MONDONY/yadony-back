package com.yadony.api.common;

/**
 * Numéro mobile money au format attendu par pawaPay : chiffres seuls, indicatif pays
 * inclus, sans {@code +}. Un E.164 Firebase ({@code +221771234567}) et une saisie libre
 * ({@code 00221 77 123 45 67}) donnent la même valeur.
 */
public final class Msisdn {

    private static final int MIN_DIGITS = 8;
    private static final int MAX_DIGITS = 15;

    private Msisdn() {}

    public static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Numéro absent");
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
            throw new IllegalArgumentException("Numéro invalide");
        }
        return digits;
    }

    /** {@code 221771234567} → {@code +221 •••• 67}. Seule forme autorisée hors du serveur. */
    public static String mask(String digits) {
        String d = normalize(digits);
        return "+" + d.substring(0, 3) + " •••• " + d.substring(d.length() - 2);
    }
}
