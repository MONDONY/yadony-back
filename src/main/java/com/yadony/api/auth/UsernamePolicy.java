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
 * l'équipe ou la modération.
 */
public final class UsernamePolicy {

    /** Cause du refus d'un identifiant. */
    public enum Violation { INVALID_FORMAT, RESERVED }

    static final Pattern FORMAT = Pattern.compile("^(?=.{3,20}$)[a-z0-9]+([._][a-z0-9]+)*$");

    static final Set<String> RESERVED_WORDS =
            Set.of("admin", "yadony", "support", "moderation", "officiel");

    private UsernamePolicy() {
    }

    /** Retire les espaces autour et passe en minuscules ; {@code null} devient la chaîne vide. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Premier motif de refus d'un identifiant déjà normalisé, vide s'il est acceptable. */
    public static Optional<Violation> violation(String normalized) {
        if (normalized == null || !FORMAT.matcher(normalized).matches()) {
            return Optional.of(Violation.INVALID_FORMAT);
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String word : RESERVED_WORDS) {
            if (lower.contains(word)) {
                return Optional.of(Violation.RESERVED);
            }
        }
        return Optional.empty();
    }
}
