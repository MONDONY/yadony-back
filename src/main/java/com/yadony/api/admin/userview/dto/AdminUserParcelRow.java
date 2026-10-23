package com.yadony.api.admin.userview.dto;

import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.MoneyAmount;

import java.time.Instant;
import java.util.UUID;

/** Une ligne de l'onglet « Colis » : {@code role} vaut {@code SENDER} ou {@code TRAVELER}. */
public record AdminUserParcelRow(
        UUID id,
        String trackingNumber,
        String corridor,
        String role,
        String status,
        Instant createdAt,
        Instant deliveredAt,
        MoneyAmount amount,
        UUID announcementId
) {}
