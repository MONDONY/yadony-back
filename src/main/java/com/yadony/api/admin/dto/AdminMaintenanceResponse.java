package com.yadony.api.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** État du mode maintenance et comptes exemptés, pour la carte Maintenance du back-office. */
public record AdminMaintenanceResponse(
        boolean enabled,
        String message,
        Instant estimatedEndAt,
        Instant startedAt,
        Instant updatedAt,
        String updatedByEmail,
        List<Exemption> exemptions) {

    public record Exemption(UUID userId, String displayName, String note, Instant createdAt, String createdByEmail) {}
}
