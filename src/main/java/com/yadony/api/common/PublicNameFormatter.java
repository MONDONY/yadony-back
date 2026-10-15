package com.yadony.api.common;

import java.util.Locale;

/**
 * Nom affichable par n'importe qui (page publique du QR colis) : prénom et
 * initiale du dernier mot. Un inconnu qui photographie un colis identifie
 * ainsi les personnes sans obtenir leur nom complet.
 */
public final class PublicNameFormatter {

    public static final String UNKNOWN = "Non précisé";

    private PublicNameFormatter() {
    }

    public static String shortName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return UNKNOWN;
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        String last = parts[parts.length - 1];
        String initial = last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        return parts[0] + " " + initial + ".";
    }
}
