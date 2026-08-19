package com.yadony.api.search;

import com.yadony.api.search.dto.RecognizedField;
import com.yadony.api.search.dto.UnresolvedItem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Porte l'état d'un parsing en cours : les tokens encore libres, les valeurs
 * trouvées, ce qui reste ambigu.
 *
 * <p>Chaque passe consomme les tokens qu'elle reconnaît. C'est ce qui permet à la
 * résolution des villes, qui passe en dernier, de ne travailler que sur du texte
 * dont personne n'a voulu.
 */
public final class ParseState {

    private final List<Token> tokens;
    private final boolean[] consumed;
    private final SearchMode mode;
    private final LocalDate today;

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<RecognizedField> recognized = new ArrayList<>();
    private final List<UnresolvedItem> unresolved = new ArrayList<>();

    public ParseState(List<Token> tokens, SearchMode mode, LocalDate today) {
        this.tokens = List.copyOf(tokens);
        this.consumed = new boolean[tokens.size()];
        this.mode = mode;
        this.today = today;
    }

    public List<Token> allTokens() { return tokens; }
    public SearchMode mode() { return mode; }
    public LocalDate today() { return today; }

    public boolean isConsumed(int index) { return consumed[index]; }

    public void consume(int fromIndex, int toIndexExclusive) {
        for (int i = fromIndex; i < toIndexExclusive && i < consumed.length; i++) {
            consumed[i] = true;
        }
    }

    /** Les tokens qu'aucune passe n'a réclamés, dans l'ordre d'origine. */
    public List<Token> remaining() {
        List<Token> free = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!consumed[i]) free.add(tokens.get(i));
        }
        return free;
    }

    public void put(String field, Object value, Token from, double confidence) {
        values.put(field, value);
        recognized.add(new RecognizedField(
                field, String.valueOf(value), new int[]{from.start(), from.end()}, confidence));
    }

    public void addUnresolved(UnresolvedKind kind, String phrase, List<String> options) {
        unresolved.add(new UnresolvedItem(kind, phrase, options));
    }

    public Map<String, Object> values() { return values; }
    public List<RecognizedField> recognized() { return recognized; }
    public List<UnresolvedItem> unresolved() { return unresolved; }

    public List<String> ignoredWords() {
        return remaining().stream().map(Token::raw).toList();
    }
}
