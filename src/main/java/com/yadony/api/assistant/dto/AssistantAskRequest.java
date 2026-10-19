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
 * @param format   {@code "rich"} : l'app sait afficher le texte riche ({@code **gras**}, puces {@code - }) et
 *                 reçoit {@code answerRich} en plus de {@code answer}. Absent ou autre valeur : réponse en
 *                 texte brut, exactement comme avant (YADONY-FLUTTER-PROD-13).
 */
public record AssistantAskRequest(
        @NotBlank @Size(max = 500) String question,
        String locale,
        @Size(max = 50) List<@Valid AssistantHistoryTurn> history,
        UUID parcelId,
        @Size(max = 16) String format) {

    public static final String FORMAT_RICH = "rich";

    public AssistantAskRequest(String question, String locale, List<AssistantHistoryTurn> history) {
        this(question, locale, history, null, null);
    }

    public AssistantAskRequest(String question, String locale, List<AssistantHistoryTurn> history, UUID parcelId) {
        this(question, locale, history, parcelId, null);
    }

    public boolean rich() {
        return FORMAT_RICH.equalsIgnoreCase(format == null ? null : format.strip());
    }
}
