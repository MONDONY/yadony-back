package com.yadony.api.common;

import com.yadony.api.matching.TransportMode;

/**
 * Libellé et symbole d'un mode de transport pour les pages publiques en français (page d'une annonce,
 * page du QR d'un colis). Un mode inconnu ({@code null}) n'a ni libellé ni symbole : la page n'en
 * affiche aucun, elle ne retombe jamais sur l'avion.
 */
public final class TransportModeLabels {

    private TransportModeLabels() {
    }

    /** « Avion », « Bateau », « Voiture »… ; {@code null} si le mode est inconnu. */
    public static String label(TransportMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case PLANE -> "Avion";
            case BOAT -> "Bateau";
            case CAR -> "Voiture";
            case BUS -> "Bus";
            case TRAIN -> "Train";
            case OTHER -> "Autre moyen";
        };
    }

    /** Symbole du mode ; {@code null} pour « autre » et pour un mode inconnu (repli neutre, sans symbole). */
    public static String symbol(TransportMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case PLANE -> "✈";
            case BOAT -> "🚢";
            case CAR -> "🚗";
            case BUS -> "🚌";
            case TRAIN -> "🚆";
            case OTHER -> null;
        };
    }
}
