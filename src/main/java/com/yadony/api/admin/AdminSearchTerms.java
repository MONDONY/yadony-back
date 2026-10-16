package com.yadony.api.admin;

import java.util.Locale;
import java.util.UUID;

/**
 * Normalisation des termes de recherche des listes admin (recherche globale de la barre du haut) :
 * un identifiant UUID canonique est reconnu tel quel, le reste devient un motif LIKE en minuscules,
 * débarrassé de {@code %} et {@code _} (la saisie est un texte, pas un motif).
 */
public final class AdminSearchTerms {

    private AdminSearchTerms() {
    }

    /** UUID canonique (36 caractères), sinon null. */
    public static UUID uuid(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code %terme%} en minuscules, ou null si la saisie est vide. */
    public static String like(String query) {
        if (query == null) {
            return null;
        }
        String needle = query.toLowerCase(Locale.ROOT).replace("%", "").replace("_", "").strip();
        return needle.isEmpty() ? null : "%" + needle + "%";
    }
}
