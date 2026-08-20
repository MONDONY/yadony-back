package com.yadony.api.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Découpe une requête libre en tokens et retire les formules d'intention.
 *
 * <p>Le bruit est retiré ici, une bonne fois, plutôt que dans chaque passe : sans
 * cela « je veux envoyer » ferait remonter « veux » comme candidat ville au dernier
 * tour, puisque la résolution des villes travaille sur ce qui reste.
 */
public final class SearchTokenizer {

    private SearchTokenizer() {}

    /**
     * Formules d'intention sans valeur de filtre.
     *
     * <p>« colis » et « paquet » en font partie, mais surtout pas « valise » ni
     * « carton » : ces deux-là portent un poids conventionnel que la passe quantités
     * exploite.
     */
    private static final Set<String> NOISE = Set.of(
            "je", "j", "veux", "voudrais", "cherche", "recherche", "besoin",
            "envoyer", "envoi", "expedier", "transporter", "emmener",
            "colis", "paquet", "bagage",
            "quelqu", "qui", "que", "quoi",
            "il", "me", "faut", "ai", "un", "une", "des", "du", "le", "la", "les",
            "s", "vous", "plait", "svp", "merci", "bonjour");

    /** Mots courts porteurs de sens qu'il ne faut jamais confondre avec du bruit. */
    private static final Set<String> KEEP = Set.of("a", "de", "en", "vers", "pour", "kg", "€");

    public static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) return tokens;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                // Les chiffres et les lettres se séparent, même collés : « 15kg »
                // doit donner un nombre puis une unité, sinon la passe quantités
                // ne voit jamais l'unité.
                int start = i;
                while (i < n && Character.isDigit(text.charAt(i))) i++;
                addIfMeaningful(tokens, text, start, i);
            } else if (Character.isLetter(c)) {
                int start = i;
                while (i < n && Character.isLetter(text.charAt(i))) i++;
                addIfMeaningful(tokens, text, start, i);
            } else if (c == '€' || c == '$') {
                addIfMeaningful(tokens, text, i, i + 1);
                i++;
            } else {
                i++;
            }
        }
        return tokens;
    }

    private static void addIfMeaningful(List<Token> tokens, String text, int start, int end) {
        String raw = text.substring(start, end);
        String normalized = normalize(raw);
        if (!KEEP.contains(normalized) && NOISE.contains(normalized)) return;
        tokens.add(new Token(raw, normalized, start, end));
    }

    /** Minuscules et suppression des diacritiques. */
    static String normalize(String raw) {
        String lower = raw.toLowerCase();
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
