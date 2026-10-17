package com.yadony.api.assistant.formdraft;

import java.util.List;
import java.util.Map;

/** Ce qui distingue un formulaire : ses champs, ses règles, ses questions. Le reste vit dans FormDraftEngine. */
public interface FormDraftSchema {

    FormKind kind();

    /** Toutes les clés du brouillon, dans l'ordre de la réponse. */
    List<String> keys();

    String systemPrompt(DraftContext ctx);

    /**
     * Garde seulement les valeurs valides (jamais de null), ajoute les champs dérivés et applique les règles
     * croisées. Ignore les clés inconnues. Idempotent.
     */
    Map<String, Object> sanitize(Map<String, ?> raw, DraftContext ctx);

    /** Champs requis encore absents, dans l'ordre où les demander. */
    List<String> missing(Map<String, Object> draft);

    /** Puces « Déjà compris » (identifiants, l'app les traduit). */
    List<String> understood(Map<String, Object> draft);

    /** Question de repli pour un champ manquant ; jamais vide. */
    String fallbackQuestion(String field, String locale);
}
