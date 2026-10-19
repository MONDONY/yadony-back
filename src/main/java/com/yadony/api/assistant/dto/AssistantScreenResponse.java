package com.yadony.api.assistant.dto;

import java.util.List;

/**
 * Explication de l'écran depuis lequel l'assistant est ouvert, lue dans une fiche : sans fournisseur
 * d'IA ni quota.
 *
 * @param questions questions proposées sous l'explication
 * @param actions   autres écrans de la fiche (jamais l'écran expliqué lui-même)
 * @param explanationRich même explication en texte riche (un paragraphe par ligne, puces {@code - }),
 *                        seulement avec {@code format=rich} et une fiche de plusieurs lignes ; sinon null
 *                        (absente du JSON) et l'app affiche {@code explanation}
 */
public record AssistantScreenResponse(String id, String title, String explanation, List<String> questions,
                                      List<AssistantAction> actions, String explanationRich) {

    public AssistantScreenResponse(String id, String title, String explanation, List<String> questions,
                                   List<AssistantAction> actions) {
        this(id, title, explanation, questions, actions, null);
    }
}
