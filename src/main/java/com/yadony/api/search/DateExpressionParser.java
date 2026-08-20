package com.yadony.api.search;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconnaît les expressions de date françaises.
 *
 * <p>Règle centrale : un mois nommé sans année désigne <strong>sa prochaine
 * occurrence</strong>. Sans elle, « mars » cherché en août porterait sur un mois
 * révolu et ne renverrait jamais rien.
 *
 * <p>Cette passe doit s'exécuter avant la résolution des villes : plusieurs noms de
 * mois sont aussi des noms de villes, et {@code pg_trgm} les capterait volontiers.
 */
public final class DateExpressionParser {

    private DateExpressionParser() {}

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("janvier", 1), Map.entry("fevrier", 2), Map.entry("mars", 3),
            Map.entry("avril", 4),   Map.entry("mai", 5),     Map.entry("juin", 6),
            Map.entry("juillet", 7), Map.entry("aout", 8),    Map.entry("septembre", 9),
            Map.entry("octobre", 10), Map.entry("novembre", 11), Map.entry("decembre", 12));

    private static final Set<String> VAGUE = Set.of("bientot", "prochainement", "rapidement");

    public static void apply(ParseState state) {
        List<Token> tokens = state.allTokens();
        LocalDate today = state.today();

        for (int i = 0; i < tokens.size(); i++) {
            if (state.isConsumed(i)) continue;
            Token t = tokens.get(i);
            String n = t.normalized();

            if (VAGUE.contains(n)) {
                state.addUnresolved(UnresolvedKind.DATE_VAGUE, t.raw(), List.of());
                state.consume(i, i + 1);
                continue;
            }

            if (n.equals("demain")) {
                // « après-demain » se découpe en deux tokens ("apres", "demain") : sans
                // ce regard en arrière, il retombait sur la branche « demain » et
                // renvoyait J+1 avec confiance — une date fausse, pas une absence
                // (trouvaille I5 de la revue).
                boolean afterTomorrow = i > 0 && !state.isConsumed(i - 1)
                        && tokens.get(i - 1).normalized().equals("apres");
                LocalDate date = afterTomorrow ? today.plusDays(2) : today.plusDays(1);
                putRange(state, date, date, t);
                state.consume(afterTomorrow ? i - 1 : i, i + 1);
                continue;
            }

            if (n.equals("weekend") || n.equals("week")) {
                state.put("weekendOnly", Boolean.TRUE, t, 1.0);
                state.consume(i, i + 1);
                // « week-end » a pu être découpé en « week » puis « end »
                if (i + 1 < tokens.size() && tokens.get(i + 1).normalized().equals("end")) {
                    state.consume(i + 1, i + 2);
                }
                continue;
            }

            if (n.equals("mois") && i + 1 < tokens.size()
                    && tokens.get(i + 1).normalized().equals("prochain")) {
                YearMonth next = YearMonth.from(today).plusMonths(1);
                putRange(state, next.atDay(1), next.atEndOfMonth(), t);
                state.consume(i, i + 2);
                continue;
            }

            Integer month = MONTHS.get(n);
            if (month != null) {
                YearMonth target = nextOccurrence(today, month);
                Integer day = precedingDay(state, tokens, i, target);
                if (day != null) {
                    LocalDate exact = target.atDay(day);
                    putRange(state, exact, exact, t);
                    state.consume(i - 1, i + 1);
                } else {
                    putRange(state, target.atDay(1), target.atEndOfMonth(), t);
                    state.consume(i, i + 1);
                }
            }
        }
    }

    /** Le mois demandé, cette année s'il n'est pas encore fini, l'an prochain sinon. */
    static YearMonth nextOccurrence(LocalDate today, int month) {
        YearMonth candidate = YearMonth.of(today.getYear(), month);
        return candidate.isBefore(YearMonth.from(today)) ? candidate.plusYears(1) : candidate;
    }

    /** Un nombre de 1 à 31 juste avant le mois, valide pour ce mois : « le 12 mars ». */
    private static Integer precedingDay(ParseState state, List<Token> tokens, int monthIndex, YearMonth target) {
        if (monthIndex == 0) return null;
        int prev = monthIndex - 1;
        if (state.isConsumed(prev)) return null;
        String p = tokens.get(prev).normalized();
        if (!p.chars().allMatch(Character::isDigit) || p.isEmpty()) return null;
        int day = Integer.parseInt(p);
        // Valider que le jour existe réellement dans ce mois (ex: 31 février invalide).
        return (day >= 1 && target.isValidDay(day)) ? day : null;
    }

    private static void putRange(ParseState state, LocalDate from, LocalDate to, Token source) {
        state.put("departureDateFrom", from, source, 1.0);
        state.put("departureDateTo", to, source, 1.0);
    }
}
