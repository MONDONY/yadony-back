package com.yadony.api.admin;

import java.util.Arrays;
import java.util.List;
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

    /** Accents français repliés ; même table que le {@code TRANSLATE} SQL (lettre pour lettre). */
    public static final String ACCENT_FROM = "àâäáãåçéèêëíìîïñóòôöõúùûüýÿœ";
    public static final String ACCENT_TO = "aaaaaaceeeeiiiinooooouuuuyyo";

    /** Minuscules, accents repliés, espaces multiples réduits ; null reste null. */
    public static String fold(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder folded = new StringBuilder(input.length());
        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            int index = ACCENT_FROM.indexOf(c);
            folded.append(index >= 0 ? ACCENT_TO.charAt(index) : c);
        }
        return folded.toString().replaceAll("\\s+", " ").strip();
    }

    /** Jetons repliés de la saisie, sans {@code %} ni {@code _}, limités à {@code max}. */
    public static List<String> tokens(String query, int max) {
        String folded = fold(query);
        if (folded == null) {
            return List.of();
        }
        return Arrays.stream(folded.replace("%", "").replace("_", "").split(" "))
                .filter(token -> !token.isEmpty())
                .limit(max)
                .toList();
    }

    /** {@code %jeton%} pour un jeton déjà replié. */
    public static String likeFolded(String token) {
        return "%" + token + "%";
    }

    /** Nombre minimal de chiffres d'une recherche par téléphone : en dessous, la saisie ne cible pas un numéro. */
    public static final int MIN_PHONE_DIGITS = 8;

    /**
     * Chiffres d'une saisie qui est un numéro de téléphone, sans zéros de tête, ou null. La saisie,
     * une fois retirés espaces, {@code +}, {@code -}, {@code .}, {@code (} et {@code )}, ne doit
     * contenir que des chiffres, au moins {@value #MIN_PHONE_DIGITS} une fois les zéros de tête
     * retirés (« 06 12 34 56 78 » trouve donc « +33612345678 »). Le téléphone stocké doit
     * <em>se terminer</em> par ce suffixe, et la saisie doit couvrir tout le numéro à l'indicatif
     * pays près (au plus {@code AdminBidSql.PHONE_MAX_UNTYPED_DIGITS} chiffres de tête non saisis,
     * vérifié en SQL) : jamais de recherche par milieu ni par préfixe, qui
     * permettrait de reconstituer chiffre par chiffre un numéro affiché masqué.
     */
    public static String phoneSuffix(String query) {
        if (query == null) {
            return null;
        }
        String digits = query.replaceAll("[\\s+\\-.()]", "");
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        String suffix = digits.replaceFirst("^0+", "");
        return suffix.length() < MIN_PHONE_DIGITS ? null : suffix;
    }
}
