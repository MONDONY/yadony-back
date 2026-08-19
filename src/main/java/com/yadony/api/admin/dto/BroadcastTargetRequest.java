package com.yadony.api.admin.dto;

import com.yadony.api.admin.broadcast.BroadcastTarget;
import com.yadony.api.admin.broadcast.BroadcastTargetType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Ciblage tel qu'il arrive du front. La coherence (corridor complet, userId present) est
 * validee par {@link BroadcastTarget}, pas ici : une seule regle, un seul endroit.
 */
public record BroadcastTargetRequest(
        @NotNull BroadcastTargetType type,
        String origin,
        String destination,
        UUID userId) {

    public BroadcastTarget toDomain() {
        return new BroadcastTarget(type, origin, destination, userId);
    }
}
