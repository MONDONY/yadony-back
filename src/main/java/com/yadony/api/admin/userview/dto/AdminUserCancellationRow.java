package com.yadony.api.admin.userview.dto;

import java.time.Instant;
import java.util.UUID;

/** Une annulation ou absence ; {@code declaredByUser} est faux quand l'utilisateur l'a subie. */
public record AdminUserCancellationRow(
        UUID id,
        UUID bidId,
        String reason,
        String scope,
        String status,
        boolean declaredByUser,
        Instant createdAt
) {}
