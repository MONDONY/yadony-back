package com.yadony.api.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Seuil d'acceptation d'un essai à deux mots, nettement plus strict que
     * {@link #THRESHOLD}. Mesuré avec la même formule que {@code pg_trgm} (Jaccard
     * sur les trigrammes) : « saint-louis » contre le vrai nom « Saint-Louis » vaut
     * 1,0, mais « paris-dakar » contre « Paris » — deux villes adjacentes sans
     * aucun rapport entre elles — vaut déjà 0,385. En dessous de ce seuil, la paire
     * n'est presque sûrement pas un nom composé et on doit se replier sur le mot
     * seul plutôt que fusionner deux villes distinctes en une ville fausse.
     */
    private static final double PAIR_ACCEPT_THRESHOLD = 0.6;

    private static final int LIMIT = 5;

    /**
     * Nombre maximum de requêtes trigramme {@code pg_trgm} par appel. Chaque requête
     * est un parcours complet de la table {@code cities} (68 5xx lignes) : l'index GIN
     * existant ne sert pas une clause {@code similarity(...) >= x}. Sans borne, une
     * phrase de 200 caractères (la limite de {@code SearchParseRequest.text}) peut
     * comporter des dizaines de tokens non reconnus et déclencher autant de parcours
     * complets pour un seul appel HTTP.
     *
     * <p>Un token candidat à une ville coûte désormais jusqu'à deux requêtes (l'essai
     * à deux mots pour les noms composés, puis le repli à un mot si le premier essai
     * ne trouve rien) : la borne double en conséquence pour laisser un corridor
     * complet « de X à Y » s'exprimer confortablement, tout en restant petite face à
     * une phrase pathologique.
     */
    private static final int MAX_LOOKUPS = 8;

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
        int lookups = 0;

        for (int i = 0; i < tokens.size(); i++) {
            if (state.isConsumed(i)) continue;
            if (lookups >= MAX_LOOKUPS) break;

            Token t = tokens.get(i);
            String n = t.normalized();

            if (TO_PREPOSITIONS.contains(n) || FROM_PREPOSITIONS.contains(n)) continue;
            if (n.length() < MIN_TOKEN_LENGTH) continue;
            if (n.chars().allMatch(Character::isDigit)) continue;

            // 27 % des lignes de `cities` portent un nom en plusieurs mots, très
            // majoritairement à trait d'union (« Saint-Louis », « Bobo-Dioulasso »,
            // « Pointe-Noire »...) que le tokenizer a déjà séparés en mots simples.
            // Regarder chaque mot seul fait remonter deux villes fausses avec
            // confiance (« saint » → « Saint-Lô », « louis » → « St. Louis ») sans
            // jamais lever d'ambiguïté : l'essai à deux mots, joint par un trait
            // d'union pour retomber sur la graphie réelle, doit donc passer avant le
            // repli mot à mot — sous réserve du PAIR_ACCEPT_THRESHOLD ci-dessus.
            Token next = nextEligiblePairToken(state, tokens, i);
            if (next != null && lookups < MAX_LOOKUPS) {
                lookups++;
                String joined = n + "-" + next.normalized();
                List<SearchCityRepository.CityMatch> pairMatches =
                        repository.findSimilar(joined, THRESHOLD, LIMIT);

                if (!pairMatches.isEmpty() && pairMatches.get(0).similarity() >= PAIR_ACCEPT_THRESHOLD) {
                    Token merged = mergeTokens(t, next);
                    if (isAmbiguous(pairMatches)) {
                        state.addUnresolved(UnresolvedKind.CITY_AMBIGUOUS, merged.raw(),
                                distinguishableOptions(pairMatches));
                    } else {
                        SearchCityRepository.CityMatch best = pairMatches.get(0);
                        found.add(new Resolved(best.name(), best.countryCode(), merged,
                                precedingPreposition(tokens, i)));
                    }
                    state.consume(i, i + 2);
                    continue;
                }
                // Rien d'assez confiant pour la paire : ce n'était pas un nom
                // composé, on retombe sur le mot seul ci-dessous.
            }

            if (lookups >= MAX_LOOKUPS) break;
            lookups++;

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

            if (isAmbiguous(matches)) {
                state.addUnresolved(UnresolvedKind.CITY_AMBIGUOUS, t.raw(), distinguishableOptions(matches));
                state.consume(i, i + 1);
                continue;
            }

            SearchCityRepository.CityMatch best = matches.get(0);
            found.add(new Resolved(best.name(), best.countryCode(), t, precedingPreposition(tokens, i)));
            state.consume(i, i + 1);
        }

        assign(state, found);
    }

    /**
     * Le token suivant, s'il peut rejoindre {@code i} pour former un essai de nom
     * composé : pas déjà consommé, pas une préposition de lieu (qui appartient à la
     * structure de la phrase, pas au nom), pas purement numérique.
     */
    private static Token nextEligiblePairToken(ParseState state, List<Token> tokens, int i) {
        int j = i + 1;
        if (j >= tokens.size() || state.isConsumed(j)) return null;
        Token next = tokens.get(j);
        String nn = next.normalized();
        if (TO_PREPOSITIONS.contains(nn) || FROM_PREPOSITIONS.contains(nn)) return null;
        if (nn.chars().allMatch(Character::isDigit)) return null;
        return next;
    }

    private static Token mergeTokens(Token a, Token b) {
        return new Token(a.raw() + " " + b.raw(), a.normalized() + " " + b.normalized(), a.start(), b.end());
    }

    /**
     * Une similarité identique entre les deux meilleurs candidats, pour un nom
     * identique, est de l'homonymie (13 lignes « Paris » en base, toutes à
     * similarité 1.0) : le départage par population, déjà fait en SQL
     * (ORDER BY ... population DESC), tranche — matches.get(0) est déjà le bon
     * candidat, inutile de demander à l'utilisateur. Ce n'est une vraie ambiguïté
     * que si les deux meilleurs candidats, à score proche, portent des noms
     * différents (ex: « Kolda » contre « Koldo »).
     */
    private static boolean isAmbiguous(List<SearchCityRepository.CityMatch> matches) {
        return matches.size() > 1
                && matches.get(0).similarity() - matches.get(1).similarity() < AMBIGUITY_MARGIN
                && !matches.get(0).name().equals(matches.get(1).name());
    }

    /**
     * Format des options d'ambiguïté : {@code "Nom (CC)"}, {@code CC} étant le code
     * pays ISO-2 de {@code cities.country_code}. Une option qui ne porterait que le
     * nom serait inexploitable côté client dès que deux candidats partagent le même
     * nom dans des pays différents (« Saint-Louis » au Sénégal et à La Réunion).
     */
    private static List<String> distinguishableOptions(List<SearchCityRepository.CityMatch> matches) {
        return matches.stream()
                .map(m -> "%s (%s)".formatted(m.name(), m.countryCode()))
                .toList();
    }

    /** La préposition juste avant le token, ou null. */
    private static Direction precedingPreposition(List<Token> tokens, int index) {
        if (index == 0) return null;
        String prev = tokens.get(index - 1).normalized();
        if (TO_PREPOSITIONS.contains(prev)) return Direction.TO;
        if (FROM_PREPOSITIONS.contains(prev)) return Direction.FROM;
        return null;
    }

    private static void assign(ParseState state, List<Resolved> rawFound) {
        if (rawFound.isEmpty()) return;

        // Deux tokens différents (mention répétée, ou deux moitiés d'un nom en
        // plusieurs mots qui convergent par hasard vers le même candidat) ne doivent
        // jamais être lus comme deux villes distinctes. On garde l'occurrence la plus
        // informative : une résolution avec direction (« à »/« depuis ») l'emporte sur
        // une résolution libre du même nom.
        List<Resolved> found = dedupeByName(rawFound);

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

        List<Resolved> unassigned;
        if (free.size() == 1 && !arrivalAlreadySet) {
            // Une ville seule est une destination : l'expéditeur nomme où il envoie,
            // rarement d'où il part.
            state.put("arrivalCity", free.get(0).name, free.get(0).token, 0.85);
            unassigned = List.of();
        } else if (free.size() >= 2 && !departureAlreadySet && !arrivalAlreadySet) {
            // Aucune préposition explicite n'a encore fixé de direction : seul ce cas
            // permet de lire « départ puis arrivée » sans écraser une décision prise
            // plus haut par « à »/« depuis ».
            state.put("departureCity", free.get(0).name, free.get(0).token, 0.85);
            state.put("arrivalCity", free.get(1).name, free.get(1).token, 0.85);
            unassigned = free.subList(2, free.size());
        } else if (free.size() == 1 && !departureAlreadySet) {
            state.put("departureCity", free.get(0).name, free.get(0).token, 0.80);
            unassigned = List.of();
        } else {
            unassigned = free;
        }

        // Des villes reconnues mais qui n'ont trouvé aucun champ libre (départ ET
        // arrivée déjà fixés par ailleurs, ou trop de villes libres dans la phrase) ne
        // doivent jamais être tues : le client doit pouvoir demander laquelle
        // l'utilisateur voulait dire plutôt que de la perdre en silence.
        for (Resolved r : unassigned) {
            state.addUnresolved(UnresolvedKind.CITY_UNASSIGNED, r.token.raw(), List.of());
        }
    }

    /** Garde la première occurrence par nom, en préférant une résolution dirigée. */
    private static List<Resolved> dedupeByName(List<Resolved> found) {
        Map<String, Resolved> byName = new LinkedHashMap<>();
        for (Resolved r : found) {
            Resolved existing = byName.get(r.name);
            if (existing == null || (existing.direction == null && r.direction != null)) {
                byName.put(r.name, r);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private enum Direction { TO, FROM }

    private record Resolved(String name, String countryCode, Token token, Direction direction) {}
}
