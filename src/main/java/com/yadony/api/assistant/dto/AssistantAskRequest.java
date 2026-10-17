package com.yadony.api.assistant.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param parcelId colis choisi plus tôt dans la conversation (bouton de choix, ou numéro reconnu) :
 *                 l'app le renvoie tant que la conversation en parle. Le back revérifie l'accès à
 *                 chaque question ; absent pour une app plus ancienne.
 */
public record AssistantAskRequest(
        @NotBlank @Size(max = 500) String question,
        String locale,
        @Size(max = 50) List<@Valid AssistantHistoryTurn> history,
        UUID parcelId) {

    public AssistantAskRequest(String question, String locale, List<AssistantHistoryTurn> history) {
        this(question, locale, history, null);
    }
}
