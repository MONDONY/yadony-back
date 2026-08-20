package com.yadony.api.search;

import com.yadony.api.config.ContentCatalog;
import com.yadony.api.config.dto.ContentCategoryResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconnaît les catégories de contenu à partir de leur vocabulaire courant.
 *
 * <p>La valeur produite est le {@code label} du catalogue, jamais le {@code code} :
 * c'est le label qui est persisté dans {@code announcement_accepted_types} et
 * {@code bids.content_category}. Un code produirait un filtre qui ne matche rien.
 */
public final class ContentTypeParser {

    private ContentTypeParser() {}

    /** Mot courant → code canonique du catalogue. */
    private static final Map<String, String> WORD_TO_CODE = buildVocabulary();

    /** Code canonique → label persisté, dérivé du catalogue pour ne jamais diverger. */
    private static final Map<String, String> CODE_TO_LABEL = ContentCatalog.CATEGORIES.stream()
            .collect(HashMap::new,
                     (m, c) -> m.put(c.code(), c.label()),
                     HashMap::putAll);

    private static Map<String, String> buildVocabulary() {
        Map<String, String> v = new HashMap<>();
        for (String w : List.of("document", "documents", "papier", "papiers", "dossier"))
            v.put(w, "DOCUMENTS");
        for (String w : List.of("riz", "mil", "farine", "epices", "nourriture", "alimentation"))
            v.put(w, "ALIMENTATION_SECHE");
        for (String w : List.of("poisson", "viande", "frais", "perissable"))
            v.put(w, "PRODUITS_FRAIS");
        for (String w : List.of("cosmetique", "cosmetiques", "parfum", "parfums", "creme"))
            v.put(w, "COSMETIQUES");
        for (String w : List.of("vetement", "vetements", "tissu", "tissus", "pagne", "habits"))
            v.put(w, "VETEMENTS");
        for (String w : List.of("chaussure", "chaussures", "basket", "baskets"))
            v.put(w, "CHAUSSURES");
        for (String w : List.of("medicament", "medicaments", "plante", "plantes", "tisane"))
            v.put(w, "MEDICAMENTS_TRADITIONNELS");
        for (String w : List.of("telephone", "telephones", "portable", "ordinateur", "electronique"))
            v.put(w, "ELECTRONIQUE");
        for (String w : List.of("livre", "livres", "cahier", "cahiers"))
            v.put(w, "LIVRES");
        for (String w : List.of("cadeau", "cadeaux", "jouet", "jouets"))
            v.put(w, "CADEAUX");
        return Map.copyOf(v);
    }

    public static void apply(ParseState state) {
        List<Token> tokens = state.allTokens();

        for (int i = 0; i < tokens.size(); i++) {
            if (state.isConsumed(i)) continue;
            Token t = tokens.get(i);

            String code = WORD_TO_CODE.get(t.normalized());
            if (code == null) continue;

            String label = CODE_TO_LABEL.get(code);
            if (label == null) continue; // le catalogue a bougé, on n'invente rien

            state.put("contentType", label, t, 0.9);
            state.consume(i, i + 1);
            return; // une seule catégorie par requête
        }
    }
}
