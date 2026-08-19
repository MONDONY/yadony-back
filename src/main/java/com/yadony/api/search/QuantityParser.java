package com.yadony.api.search;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconnaît les poids et les prix.
 *
 * <p>Le poids change de sens selon le mode, et c'est la subtilité de cette passe.
 * En mode {@code TRIPS} l'utilisateur est un expéditeur qui décrit <em>son colis</em> :
 * le trajet doit donc disposer d'au moins cette capacité ({@code minAvailableKg}).
 * En mode {@code PACKAGES} c'est un voyageur qui décrit <em>sa capacité</em>, et la
 * même phrase borne le poids des colis proposés ({@code maxWeight}).
 */
public final class QuantityParser {

    private QuantityParser() {}

    private static final Set<String> WEIGHT_UNITS = Set.of("kg", "kilo", "kilos", "kilogramme", "kilogrammes");
    private static final Set<String> PRICE_UNITS  = Set.of("€", "euro", "euros", "balles", "eur");

    /** Poids conventionnel des contenants nommés en langage courant. */
    private static final Map<String, BigDecimal> LUGGAGE = Map.of(
            "valise", new BigDecimal("23"),
            "carton", new BigDecimal("15"),
            "sac",    new BigDecimal("10"));

    private static final Map<String, BigDecimal> SPELLED = Map.ofEntries(
            Map.entry("cinq", new BigDecimal("5")),
            Map.entry("dix", new BigDecimal("10")),
            Map.entry("quinze", new BigDecimal("15")),
            Map.entry("vingt", new BigDecimal("20")),
            Map.entry("trente", new BigDecimal("30")),
            Map.entry("quarante", new BigDecimal("40")),
            Map.entry("cinquante", new BigDecimal("50")));

    private static final Set<String> VAGUE_PRICE = Set.of("cher", "chere", "economique", "abordable");

    public static void apply(ParseState state) {
        List<Token> tokens = state.allTokens();

        for (int i = 0; i < tokens.size(); i++) {
            if (state.isConsumed(i)) continue;
            Token t = tokens.get(i);

            // « une valise », « un carton »
            BigDecimal luggage = LUGGAGE.get(t.normalized());
            if (luggage != null) {
                putWeight(state, luggage, t);
                state.consume(i, i + 1);
                continue;
            }

            // « pas trop cher » : la question est posée, aucun seuil n'est inventé
            if (VAGUE_PRICE.contains(t.normalized())) {
                state.addUnresolved(UnresolvedKind.PRICE_VAGUE, t.raw(),
                        List.of("6", "9", "unlimited"));
                state.consume(i, i + 1);
                continue;
            }

            BigDecimal amount = amountOf(t);
            if (amount == null) continue;

            // L'unité suit le nombre : « 20 kg », « 8 € ». Elle peut aussi être
            // collée, auquel cas le tokeniseur a déjà séparé chiffres et lettres.
            String unit = (i + 1 < tokens.size()) ? tokens.get(i + 1).normalized() : "";

            if (WEIGHT_UNITS.contains(unit)) {
                putWeight(state, amount, t);
                state.consume(i, i + 2);
            } else if (PRICE_UNITS.contains(unit)) {
                state.put("maxPricePerKg", amount, t, 1.0);
                state.consume(i, i + 2);
            }
        }
    }

    private static void putWeight(ParseState state, BigDecimal kg, Token from) {
        String field = state.mode() == SearchMode.TRIPS ? "minAvailableKg" : "maxWeight";
        state.put(field, kg, from, 1.0);
    }

    private static BigDecimal amountOf(Token t) {
        String n = t.normalized();
        BigDecimal spelled = SPELLED.get(n);
        if (spelled != null) return spelled;
        if (n.chars().allMatch(Character::isDigit) && !n.isEmpty()) return new BigDecimal(n);
        return null;
    }
}
