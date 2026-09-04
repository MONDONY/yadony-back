package com.yadony.api.alerts.dto;

import com.yadony.api.alerts.AlertDirection;
import com.yadony.api.alerts.AlertNotifyMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CorridorAlertResponse(
        UUID id,
        String departureCity,
        String arrivalCity,
        String departureCountryCode,
        String arrivalCountryCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal minWeightKg,
        List<String> contentCategories,
        AlertDirection direction,
        boolean active,
        long matchCount,
        LocalDateTime createdAt,
        // ── Zone de remise optionnelle (SENDER_WANTS_TRIPS) ─────────────────
        BigDecimal centerLat,
        BigDecimal centerLng,
        Integer radiusKm,
        String centerLabel,
        /**
         * Correspondances apparues depuis la dernière consultation des matchs
         * par le propriétaire (toutes, s'il ne les a jamais ouverts). C'est ce
         * chiffre que le hub Activités et la liste mettent en avant, jamais
         * {@link #matchCount} qui ne dit rien de ce qui a changé.
         */
        long newMatchCount,
        /** Dernière ouverture des correspondances ; {@code null} = jamais. */
        LocalDateTime lastSeenAt,
        AlertNotifyMode notifyMode
) {
    /** Constructeur de compat (sans zone de remise) — délègue avec une zone nulle. */
    public CorridorAlertResponse(
            UUID id, String departureCity, String arrivalCity,
            String departureCountryCode, String arrivalCountryCode,
            LocalDate dateFrom, LocalDate dateTo, BigDecimal minWeightKg,
            List<String> contentCategories, AlertDirection direction,
            boolean active, long matchCount, LocalDateTime createdAt) {
        this(id, departureCity, arrivalCity, departureCountryCode, arrivalCountryCode,
                dateFrom, dateTo, minWeightKg, contentCategories, direction,
                active, matchCount, createdAt, null, null, null, null, 0L, null,
                AlertNotifyMode.INSTANT);
    }
}
