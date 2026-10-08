package com.yadony.api.admin.dto;

import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AdminAnnouncementListItemResponse(
    UUID id,
    String status,
    String travelerName,
    String corridor,
    LocalDate departureDate,
    BigDecimal availableKg,
    BigDecimal pricePerKg,
    /** Devise de pricePerKg (code ISO en majuscules). */
    String currency,
    /** Voyage à plusieurs étapes (FLUTTER-4D) : identifiant commun, null hors voyage. */
    UUID tripGroupId,
    /** Rang de l'étape dans son voyage (à partir de 1), null hors voyage. */
    Integer tripLegIndex,
    /** Nombre d'étapes encore présentes dans le voyage, null hors voyage. */
    Integer tripLegCount
) {

    /** Ligne de back-office d'une annonce ; {@code tripLegCount} est ignoré hors voyage. */
    public static AdminAnnouncementListItemResponse of(AnnouncementEntity a, String travelerName,
                                                       Integer tripLegCount) {
        boolean grouped = a.getTripGroupId() != null;
        return new AdminAnnouncementListItemResponse(
                a.getId(), a.getStatus().name(), travelerName,
                MatchingTextUtil.corridorLabel(a.getDepartureCity(), a.getArrivalCity()),
                a.getDepartureDate(), a.getAvailableKg(), a.getPricePerKg(),
                a.getCurrency() != null ? a.getCurrency().toUpperCase(java.util.Locale.ROOT) : null,
                a.getTripGroupId(),
                grouped ? a.getTripLegIndex() : null,
                grouped ? tripLegCount : null);
    }
}
