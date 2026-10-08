package com.yadony.api.cancellation.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Réponse de {@code POST /cancellations/trip}.
 *
 * @param affectedBidsCount   colis annulés par l'annulation du trajet, remis ou non
 * @param parcelsToReturnCount parmi eux, colis déjà remis au voyageur, à restituer à leur
 *                            expéditeur contre un code de retour (FLUTTER-FH). Champ ajouté :
 *                            une app plus ancienne l'ignore.
 */
public record CancellationResponse(
        UUID announcementId,
        int affectedBidsCount,
        String reason,
        List<RematchSuggestionDto> rematchSuggestions,
        LocalDateTime cancelledAt,
        int parcelsToReturnCount
) {}
