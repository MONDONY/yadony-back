package com.yadony.api.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Règles de forme d'un identifiant public (username) : 3 à 20 caractères, minuscules, chiffres,
 * points et underscores, sans séparateur en tête, en queue ni doublé.
 *
 * <p>Les mots réservés sont comparés sans casse et par inclusion : {@code yadony.support} est
 * refusé parce qu'il contient {@code yadony}. Ils évitent qu'un compte se fasse passer pour
 * l'équipe ou la modération ; les sosies chiffrés (0, 1, 3 pour o, i, e) sont rapprochés avant le test, donc
 * {@code yad0ny} l'est aussi. Un identifiant contenant 7 chiffres de suite est refusé (il passerait pour un
 * numéro de téléphone), sauf la forme générée {@code user<chiffres>}. Ces refus valent à la création et au
 * changement ; la recherche d'un compte existant ne regarde que le format.
 */
public final class UsernamePolicy {

    /** Cause du refus d'un identifiant. */
    public enum Violation { INVALID_FORMAT, RESERVED, LOOKS_LIKE_PHONE }

    static final Pattern FORMAT = Pattern.compile("^(?=.{3,20}$)[a-z0-9]+([._][a-z0-9]+)*$");

    /**
     * Racines réservées, cherchées par inclusion après normalisation des sosies (0/1/3). {@code modera}
     * couvre moderateur, moderator et moderation ; {@code offici} couvre officiel et official.
     */
    static final Set<String> RESERVED_WORDS =
            Set.of("admin", "yadony", "support", "modera", "offici", "staff", "equipe");

    /** Suite de 7 chiffres ou plus : un identifiant public ne doit pas pouvoir passer pour un numéro de téléphone. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{7,}");

    /** Forme générée à l'inscription ({@code user1785012345}) : reste permise malgré la suite de chiffres. */
    private static final Pattern LEGACY_GENERATED = Pattern.compile("^user\\d+$");

    private UsernamePolicy() {
    }

    /** Retire les espaces autour et passe en minuscules ; {@code null} devient la chaîne vide. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Le format seul (sans mots réservés ni règle téléphone) : c'est lui qui décide si une recherche touche la base. */
    public static boolean isWellFormed(String normalized) {
        return normalized != null && FORMAT.matcher(normalized).matches();
    }

    /** Premier motif de refus d'un identifiant déjà normalisé, vide s'il est acceptable. */
    public static Optional<Violation> violation(String normalized) {
        if (!isWellFormed(normalized)) {
            return Optional.of(Violation.INVALID_FORMAT);
        }
        String lookAlike = normalized.toLowerCase(Locale.ROOT)
                .replace('0', 'o').replace('1', 'i').replace('3', 'e');
        for (String word : RESERVED_WORDS) {
            if (lookAlike.contains(word)) {
                return Optional.of(Violation.RESERVED);
            }
        }
        if (DIGIT_RUN.matcher(normalized).find() && !LEGACY_GENERATED.matcher(normalized).matches()) {
            return Optional.of(Violation.LOOKS_LIKE_PHONE);
        }
        return Optional.empty();
    }
}
