package com.yadony.api.admin.userview.dto;

import java.time.Instant;
import java.util.UUID;

/** Avis reçu par un utilisateur. */
public record AdminUserRatingRow(
        UUID id,
        UUID bidId,
        int stars,
        String comment,
        boolean flagged,
        Instant createdAt
) {}
