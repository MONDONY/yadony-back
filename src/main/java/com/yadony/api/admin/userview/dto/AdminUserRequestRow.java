package com.yadony.api.admin.userview.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Demande d'envoi publiée par un utilisateur. {@code desiredDate} est le jour souhaité (minuit UTC),
 * null quand la date est facultative et non renseignée.
 */
public record AdminUserRequestRow(
        UUID id,
        String corridor,
        String status,
        Double weightKg,
        Instant desiredDate,
        Instant createdAt,
        int openNegotiationCount
) {}
