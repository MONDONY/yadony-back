package com.yadony.api.assistant.dto;

import java.util.List;
import java.util.UUID;

/**
 * @param parcelId colis dont parle la réponse, que l'app renvoie avec les questions suivantes
 * @param parcels  colis à choisir quand la question porte sur l'un des siens sans dire lequel
 */
public record AssistantAskResponse(String answer, boolean answered, List<AssistantAction> actions,
                                   List<String> sources, String provider, int remainingToday,
                                   UUID parcelId, List<AssistantParcelChoice> parcels) {

    public AssistantAskResponse(String answer, boolean answered, List<AssistantAction> actions,
                                List<String> sources, String provider, int remainingToday) {
        this(answer, answered, actions, sources, provider, remainingToday, null, List.of());
    }
}
