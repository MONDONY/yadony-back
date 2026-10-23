package com.yadony.api.admin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;
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

    /**
     * Accents français repliés ; même table que le {@code TRANSLATE} SQL (lettre pour lettre). Les
     * majuscules accentuées y figurent aussi : le SQL ne dépend ainsi pas de la locale de la base
     * (lc_ctype) pour le {@code LOWER} des majuscules accentuées.
     */
    public static final String ACCENT_FROM = "àâäáãåçéèêëíìîïñóòôöõúùûüýÿœ" + "ÀÂÄÁÃÅÇÉÈÊËÍÌÎÏÑÓÒÔÖÕÚÙÛÜÝŸŒ";
    public static final String ACCENT_TO = "aaaaaaceeeeiiiinooooouuuuyyo" + "aaaaaaceeeeiiiinooooouuuuyyo";

    /** Minuscules, accents repliés, apostrophes (droite et typographique) retirées, espaces réduits ; null reste null. */
    public static String fold(String input) {
        if (input == null) {
            return null;
        }
        StringBuilder folded = new StringBuilder(input.length());
        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '\'' || c == '\u2019') {
                continue; // « N'Diaye » se trouve par « ndiaye » ; même retrait en SQL (REPLACE imbriqués)
            }
            int index = ACCENT_FROM.indexOf(c);
            folded.append(index >= 0 ? ACCENT_TO.charAt(index) : c);
        }
        return folded.toString().replaceAll("\\s+", " ").strip();
    }

    /** Jetons repliés de la saisie, sans {@code %}, {@code _} ni barre oblique inverse (échappement de LIKE), limités à {@code max}. */
    public static List<String> tokens(String query, int max) {
        String folded = fold(query);
        if (folded == null) {
            return List.of();
        }
        return Arrays.stream(folded.replace("%", "").replace("_", "").replace("\\", "").split(" "))
                .filter(token -> !token.isEmpty())
                .limit(max)
                .toList();
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

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,13})(?:[.,](\\d{1,2}))?");
    private static final Pattern AMOUNT_NOISE =
            Pattern.compile("[\\s\\u00a0\\u202f\u20ac$]|cfa|xof|xaf|f", Pattern.CASE_INSENSITIVE);

    /**
     * Montant saisi en centimes : entier ou décimal (virgule ou point, deux décimales au plus), après
     * retrait des espaces (insécables compris) et des symboles {@code € $ F CFA XOF XAF}. Tout le reste
     * (lettres, signe, trois décimales) donne vide : la saisie n'est alors pas un montant.
     */
    public static OptionalLong amountCents(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        var matcher = AMOUNT.matcher(AMOUNT_NOISE.matcher(input).replaceAll(""));
        if (!matcher.matches()) {
            return OptionalLong.empty();
        }
        String decimals = matcher.group(2) == null ? "00" : (matcher.group(2) + "0").substring(0, 2);
        return OptionalLong.of(Long.parseLong(matcher.group(1)) * 100 + Long.parseLong(decimals));
    }
}
