package com.yadony.api.messaging.dto;

import java.util.List;
import java.util.UUID;

/**
 * Résultat d'une action groupée : une ligne par identifiant distinct, dans l'ordre reçu.
 * Un échec sur un fil n'annule pas les autres (échec partiel), le client relit
 * {@code results} pour savoir lesquels garder sélectionnés.
 */
public record BatchConversationResponse(List<Item> results, int succeeded, int failed) {

    /**
     * @param status code HTTP qu'aurait rendu l'appel unitaire (204 en cas de succès)
     * @param error  code d'erreur court, nul en cas de succès
     */
    public record Item(UUID id, boolean success, int status, String error) {}

    public static BatchConversationResponse of(List<Item> results) {
        int ok = (int) results.stream().filter(Item::success).count();
        return new BatchConversationResponse(results, ok, results.size() - ok);
    }
}
