package com.yadony.api.alerts.dto;

import com.yadony.api.alerts.AlertDirection;
import com.yadony.api.alerts.AlertNotifyMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CorridorAlertRequest(
        @NotBlank String departureCity,
        String departureCountryCode,
        @NotBlank String arrivalCity,
        String arrivalCountryCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal minWeightKg,
        List<String> contentCategories,
        @NotNull AlertDirection direction,
        Boolean active,
        // ── Zone de remise optionnelle (SENDER_WANTS_TRIPS) ─────────────────
        BigDecimal centerLat,
        BigDecimal centerLng,
        Integer radiusKm,
        String centerLabel,
        /** Fréquence des notifications ; {@code null} = INSTANT (client antérieur). */
        AlertNotifyMode notifyMode
) {
    /** Constructeur de compat (sans zone de remise) — délègue avec une zone nulle. */
    public CorridorAlertRequest(
            String departureCity, String departureCountryCode,
            String arrivalCity, String arrivalCountryCode,
            LocalDate dateFrom, LocalDate dateTo, BigDecimal minWeightKg,
            List<String> contentCategories, AlertDirection direction, Boolean active) {
        this(departureCity, departureCountryCode, arrivalCity, arrivalCountryCode,
                dateFrom, dateTo, minWeightKg, contentCategories, direction, active,
                null, null, null, null, null);
    }

    /** Constructeur de compat (zone de remise, sans fréquence). */
    public CorridorAlertRequest(
            String departureCity, String departureCountryCode,
            String arrivalCity, String arrivalCountryCode,
            LocalDate dateFrom, LocalDate dateTo, BigDecimal minWeightKg,
            List<String> contentCategories, AlertDirection direction, Boolean active,
            BigDecimal centerLat, BigDecimal centerLng, Integer radiusKm, String centerLabel) {
        this(departureCity, departureCountryCode, arrivalCity, arrivalCountryCode,
                dateFrom, dateTo, minWeightKg, contentCategories, direction, active,
                centerLat, centerLng, radiusKm, centerLabel, null);
    }

    /** Fréquence effective : INSTANT quand le client n'en envoie pas. */
    public AlertNotifyMode effectiveNotifyMode() {
        return notifyMode != null ? notifyMode : AlertNotifyMode.INSTANT;
    }
}
