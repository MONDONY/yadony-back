package com.yadony.api.admin.userview.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Synthèse d'un utilisateur pour la fiche admin. Tout champ interdit à l'appelant ou pas encore
 * calculé vaut {@code null} (jamais 0) : 0 signifie « calculé, aucun ».
 */
public record AdminUserOverviewResponse(
        Kpis kpis,
        List<InProgressItem> inProgress,
        List<TimelineEntry> timeline
) {

    public record Kpis(
            Integer parcelsInProgress,
            Integer activeTrips,
            List<MoneyAmount> heldByCurrency,
            Integer openDisputes,
            Integer totalDisputes,
            Integer reportsReceived,
            Integer reportsSent,
            Integer reportsPending,
            Double averageRating,
            Integer ratingCount
    ) {}

    /** {@code amountCents} est dans la plus petite unité de {@code currency} (EUR 2 décimales, XOF 0). */
    public record MoneyAmount(String currency, long amountCents) {}

    /** {@code kind} vaut {@code PARCEL} ou {@code TRIP}. */
    public record InProgressItem(
            String kind,
            UUID id,
            String reference,
            String corridor,
            String role,
            String status,
            Instant updatedAt,
            MoneyAmount amount
    ) {}

    public record TimelineEntry(
            String kind,
            String title,
            String detail,
            Instant at,
            String refType,
            UUID refId
    ) {}
}
