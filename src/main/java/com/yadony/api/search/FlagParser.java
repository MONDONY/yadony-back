package com.yadony.api.search;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconnaît les critères booléens et le mode de transport.
 *
 * <p>Le seuil de « bien noté » vaut 4.5 pour coller exactement à la puce
 * « Note ≥ 4.5 » de l'écran : une phrase et un tap doivent donner le même résultat,
 * sinon l'utilisateur voit deux comptes différents pour la même intention.
 */
public final class FlagParser {

    private FlagParser() {}

    private static final Set<String> URGENT   = Set.of("urgent", "urgente", "urgence", "presse");
    private static final Set<String> VERIFIED = Set.of("verifie", "verifiee", "verifies", "identite");
    // Les tokens arrivent déjà sans accent : inutile de lister « noté ».
    private static final Set<String> WELL_RATED = Set.of("note", "notee", "notes");

    private static final Map<String, String> TRANSPORT = Map.of(
            "avion", "PLANE",
            "bateau", "BOAT",
            "voiture", "CAR",
            "bus", "BUS",
            "train", "TRAIN");

    public static void apply(ParseState state) {
        List<Token> tokens = state.allTokens();

        for (int i = 0; i < tokens.size(); i++) {
            if (state.isConsumed(i)) continue;
            Token t = tokens.get(i);
            String n = t.normalized();

            if (URGENT.contains(n)) {
                state.put("urgent", Boolean.TRUE, t, 1.0);
                state.consume(i, i + 1);
                continue;
            }

            if (VERIFIED.contains(n)) {
                state.put("kycVerifiedOnly", Boolean.TRUE, t, 1.0);
                state.consume(i, i + 1);
                continue;
            }

            // « kilo pro » : deux tokens, et « pro » seul suffit
            if (n.equals("pro")) {
                state.put("kiloProOnly", Boolean.TRUE, t, 1.0);
                state.consume(i, i + 1);
                if (i > 0 && tokens.get(i - 1).normalized().startsWith("kilo")) {
                    state.consume(i - 1, i);
                }
                continue;
            }

            // « bien noté », « bonne note »
            if (WELL_RATED.contains(n)) {
                state.put("minRating", new BigDecimal("4.5"), t, 0.8);
                state.consume(i, i + 1);
                if (i > 0) {
                    String prev = tokens.get(i - 1).normalized();
                    if (prev.equals("bien") || prev.equals("bonne") || prev.equals("bon")) {
                        state.consume(i - 1, i);
                    }
                }
                continue;
            }

            String transport = TRANSPORT.get(n);
            if (transport != null) {
                state.put("transportMode", transport, t, 1.0);
                state.consume(i, i + 1);
            }
        }
    }
}
