package com.yadony.api.tracking.dto;

import java.time.OffsetDateTime;

/**
 * Demande de nouveau code de retrait transmise à l'expéditeur (FLUTTER-G2).
 * {@code nextRequestAllowedAt} : instant (UTC) à partir duquel le voyageur peut relancer.
 */
public record PickupCodeRequestResponse(
        OffsetDateTime requestedAt,
        OffsetDateTime nextRequestAllowedAt
) {}
