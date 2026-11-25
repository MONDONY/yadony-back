package com.yadony.api.tracking.dto;

import java.util.UUID;

/**
 * @param unchanged le colis avait déjà ce statut : rien n'a été refait (appel idempotent)
 */
public record AdminBidStatusChangeResponse(
        UUID bidId,
        String status,
        String previousStatus,
        boolean unchanged
) {
}
