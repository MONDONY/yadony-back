package com.yadony.api.auth;

import java.security.SecureRandom;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Propose jusqu'à trois identifiants libres proches d'un identifiant refusé parce que déjà pris.
 *
 * <p>Chaque proposition est valide au sens de {@link UsernamePolicy} et libre selon le prédicat
 * fourni ; la base est tronquée pour laisser la place au suffixe sans dépasser 20 caractères.
 */
public final class UsernameSuggester {

    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_LENGTH = 20;
    /** Tentatives de suffixe numérique aléatoire, au-delà on renonce plutôt que de boucler. */
    private static final int MAX_RANDOM_ATTEMPTS = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private UsernameSuggester() {
    }

    public static List<String> suggest(String normalizedBase, Predicate<String> isFree) {
        Set<String> found = new LinkedHashSet<>();
        String year = String.valueOf(Year.now().getValue());

        tryAdd(found, normalizedBase, ".d", normalizedBase, isFree);
        tryAdd(found, normalizedBase, "_" + twoDigits(), normalizedBase, isFree);
        tryAdd(found, normalizedBase, year, normalizedBase, isFree);
        for (int i = 0; i < MAX_RANDOM_ATTEMPTS && found.size() < MAX_SUGGESTIONS; i++) {
            tryAdd(found, normalizedBase, "_" + twoDigits(), normalizedBase, isFree);
        }
        return new ArrayList<>(found);
    }

    private static void tryAdd(Set<String> found, String base, String suffix,
                               String original, Predicate<String> isFree) {
        if (found.size() >= MAX_SUGGESTIONS) {
            return;
        }
        int room = MAX_LENGTH - suffix.length();
        String head = base.length() > room ? base.substring(0, room) : base;
        // Une troncature ne doit pas laisser un séparateur en queue (« awa. » + « .d »).
        while (!head.isEmpty() && (head.endsWith(".") || head.endsWith("_"))) {
            head = head.substring(0, head.length() - 1);
        }
        String candidate = head + suffix;
        if (!candidate.equals(original)
                && UsernamePolicy.violation(candidate).isEmpty()
                && isFree.test(candidate)) {
            found.add(candidate);
        }
    }

    private static String twoDigits() {
        return String.format("%02d", RANDOM.nextInt(100));
    }
}
