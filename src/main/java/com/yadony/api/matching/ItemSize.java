package com.yadony.api.matching;

import java.util.Locale;

/**
 * Taille d'un colis (demande, réservation) ou taille maximale acceptée par un trajet route.
 * L'ordre de déclaration est l'ordre de grandeur : S < M < L < XL ({@link #fitsWithin}).
 */
public enum ItemSize {
    S,
    M,
    L,
    XL;

    /** Vrai quand ce colis tient dans une taille maximale {@code max} (égalité comprise). */
    public boolean fitsWithin(ItemSize max) {
        return ordinal() <= max.ordinal();
    }

    /** Lecture tolérante (casse et espaces) ; valeur absente, vide ou inconnue : {@code null}. */
    public static ItemSize parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ItemSize size : values()) {
            if (size.name().equals(normalized)) {
                return size;
            }
        }
        return null;
    }
}
