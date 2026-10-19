package com.yadony.api.assistant.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param parcelId    colis dont parle la réponse, que l'app renvoie avec les questions suivantes
 * @param parcels     colis à choisir quand la question porte sur l'un des siens sans dire lequel
 * @param suggestions au plus 2 {@code FOLLOW_UP} puis au plus 1 {@code DISCOVERY}, dans cet ordre ;
 *                    vide (jamais null) quand la réponse n'en propose pas
 * @param answerRich  même réponse en texte riche ({@code **gras**}, puces {@code - }), seulement quand la
 *                    question demande {@code format=rich} et que la réponse vient du modèle ; sinon null,
 *                    donc absente du JSON, et l'app affiche {@code answer}, toujours en texte brut
 */
public record AssistantAskResponse(String answer, boolean answered, List<AssistantAction> actions,
                                   List<String> sources, String provider, int remainingToday,
                                   UUID parcelId, List<AssistantParcelChoice> parcels,
                                   List<AssistantSuggestion> suggestions, String answerRich) {

    public AssistantAskResponse {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public AssistantAskResponse(String answer, boolean answered, List<AssistantAction> actions,
                                List<String> sources, String provider, int remainingToday,
                                UUID parcelId, List<AssistantParcelChoice> parcels,
                                List<AssistantSuggestion> suggestions) {
        this(answer, answered, actions, sources, provider, remainingToday, parcelId, parcels, suggestions, null);
    }

    public AssistantAskResponse(String answer, boolean answered, List<AssistantAction> actions,
                                List<String> sources, String provider, int remainingToday,
                                UUID parcelId, List<AssistantParcelChoice> parcels) {
        this(answer, answered, actions, sources, provider, remainingToday, parcelId, parcels, List.of());
    }

    public AssistantAskResponse(String answer, boolean answered, List<AssistantAction> actions,
                                List<String> sources, String provider, int remainingToday) {
        this(answer, answered, actions, sources, provider, remainingToday, null, List.of(), List.of());
    }
}
