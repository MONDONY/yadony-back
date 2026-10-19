package com.yadony.api.common;

import java.util.Locale;

/**
 * Lecture best-effort du User-Agent des pages publiques (annonce, suivi destinataire) : le
 * système du téléphone, pour proposer le bon store. Pas besoin d'être infaillible : un
 * visiteur mal identifié n'a simplement pas de bouton store, jamais un bouton faux.
 */
public final class UserAgents {

    public enum MobileOs { ANDROID, IOS, OTHER }

    private UserAgents() {}

    public static MobileOs mobileOs(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return MobileOs.OTHER;
        }
        String lower = userAgent.toLowerCase(Locale.ROOT);
        if (lower.contains("android")) {
            return MobileOs.ANDROID;
        }
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ipod")) {
            return MobileOs.IOS;
        }
        return MobileOs.OTHER;
    }

    /**
     * URL de store venue d'une variable d'environnement, ou {@code null} si elle est vide ou
     * vaut un littéral de « pas de valeur » ({@code null}, {@code none}, {@code -}) : un bouton
     * {@code href="null"} serait un bouton mort.
     */
    public static String configuredUrl(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("none")
                || trimmed.equals("-")) {
            return null;
        }
        return trimmed;
    }

    /** Store du système détecté, {@code null} si le système est inconnu ou son store non configuré. */
    public static String storeUrlFor(String userAgent, String iosUrl, String androidUrl) {
        return switch (mobileOs(userAgent)) {
            case IOS -> configuredUrl(iosUrl);
            case ANDROID -> configuredUrl(androidUrl);
            case OTHER -> null;
        };
    }
}
