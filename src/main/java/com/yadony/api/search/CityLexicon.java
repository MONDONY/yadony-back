package com.yadony.api.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Résout les tokens restants en villes et décide de leur direction.
 *
 * <p>Cette passe s'exécute <strong>en dernier</strong>. Les trigrammes de
 * {@code pg_trgm} matchent volontiers un nom de mois ou un mot courant sur une ville
 * homonyme : « mars » deviendrait une destination si la passe dates ne l'avait pas
 * consommé avant.
 */
@Component
public class CityLexicon {

    /** En deçà, le candidat n'a plus rien à voir avec ce qui a été tapé. */
    private static final double THRESHOLD = 0.4;

    /** Deux candidats plus proches que cela ne se départagent pas honnêtement. */
    private static final double AMBIGUITY_MARGIN = 0.05;

    private static final int LIMIT = 5;

    private static final Set<String> TO_PREPOSITIONS   = Set.of("a", "vers", "pour", "jusqu");
    private static final Set<String> FROM_PREPOSITIONS = Set.of("de", "depuis", "d");

    /** Un token de moins de 3 lettres ne vaut pas une requête trigramme. */
    private static final int MIN_TOKEN_LENGTH = 3;

    private final SearchCityRepository repository;

    public CityLexicon(SearchCityRepository repository) {
        this.repository = repository;
    }

    public void apply(ParseState state) {
        List<Token> tokens = state.allTokens();
        List<Resolved> found = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            if (state.isConsumed(i)) continue;
            Token t = tokens.get(i);
            String n = t.normalized();

            if (TO_PREPOSITIONS.contains(n) || FROM_PREPOSITIONS.contains(n)) continue;
            if (n.length() < MIN_TOKEN_LENGTH) continue;
            if (n.chars().allMatch(Character::isDigit)) continue;

            List<SearchCityRepository.CityMatch> matches =
                    repository.findSimilar(n, THRESHOLD, LIMIT);

            if (matches.isEmpty()) {
                // Un mot inconnu n'est signalé que s'il suit une préposition de lieu :
                // sinon toute la phrase remonterait comme ville introuvable.
                if (precedingPreposition(tokens, i) != null) {
                    state.addUnresolved(UnresolvedKind.CITY_UNKNOWN, t.raw(), List.of());
                    state.consume(i, i + 1);
                }
                continue;
            }

            if (matches.size() > 1
                    && matches.get(0).similarity() - matches.get(1).similarity() < AMBIGUITY_MARGIN) {
                state.addUnresolved(UnresolvedKind.CITY_AMBIGUOUS, t.raw(),
                        matches.stream().map(SearchCityRepository.CityMatch::name).toList());
                state.consume(i, i + 1);
                continue;
            }

            found.add(new Resolved(matches.get(0).name(), t, precedingPreposition(tokens, i)));
            state.consume(i, i + 1);
        }

        assign(state, found);
    }

    /** La préposition juste avant le token, ou null. */
    private static Direction precedingPreposition(List<Token> tokens, int index) {
        if (index == 0) return null;
        String prev = tokens.get(index - 1).normalized();
        if (TO_PREPOSITIONS.contains(prev)) return Direction.TO;
        if (FROM_PREPOSITIONS.contains(prev)) return Direction.FROM;
        return null;
    }

    private static void assign(ParseState state, List<Resolved> found) {
        if (found.isEmpty()) return;

        for (Resolved r : found) {
            if (r.direction == Direction.TO) {
                state.put("arrivalCity", r.name, r.token, 0.95);
            } else if (r.direction == Direction.FROM) {
                state.put("departureCity", r.name, r.token, 0.95);
            }
        }

        List<Resolved> free = found.stream().filter(r -> r.direction == null).toList();
        boolean arrivalAlreadySet = state.values().containsKey("arrivalCity");
        boolean departureAlreadySet = state.values().containsKey("departureCity");

        if (free.size() == 1 && !arrivalAlreadySet) {
            // Une ville seule est une destination : l'expéditeur nomme où il envoie,
            // rarement d'où il part.
            state.put("arrivalCity", free.get(0).name, free.get(0).token, 0.85);
        } else if (free.size() >= 2 && !departureAlreadySet && !arrivalAlreadySet) {
            // Aucune préposition explicite n'a encore fixé de direction : seul ce cas
            // permet de lire « départ puis arrivée » sans écraser une décision prise
            // plus haut par « à »/« depuis ».
            state.put("departureCity", free.get(0).name, free.get(0).token, 0.85);
            state.put("arrivalCity", free.get(1).name, free.get(1).token, 0.85);
        } else if (free.size() == 1 && !departureAlreadySet) {
            state.put("departureCity", free.get(0).name, free.get(0).token, 0.80);
        }
    }

    private enum Direction { TO, FROM }

    private record Resolved(String name, Token token, Direction direction) {}
}
