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
    /** Kilos restants ; null pour un trajet bateau au m³. */
    BigDecimal availableKg,
    /** Prix au kilo ; null pour un trajet bateau au m³ (voir pricePerM3). */
    BigDecimal pricePerKg,
    /** Devise de pricePerKg (code ISO en majuscules). */
    String currency,
    /** Voyage à plusieurs étapes (FLUTTER-4D) : identifiant commun, null hors voyage. */
    UUID tripGroupId,
    /** Rang de l'étape dans son voyage (à partir de 1), null hors voyage. */
    Integer tripLegIndex,
    /** Nombre d'étapes encore présentes dans le voyage, null hors voyage. */
    Integer tripLegCount,
    /** Mode du trajet (nom d'enum : PLANE, BOAT, CAR, BUS…) ; null seulement pour une ligne héritée sans mode. */
    String transportMode,
    /** Volume total proposé en m³ (bateau) ; null hors bateau au m³. */
    BigDecimal volumeCapacityM3,
    /** Volume restant en m³ (bateau) ; null hors bateau au m³. */
    BigDecimal availableVolumeM3,
    /** Prix au m³, dans {@code currency} (bateau) ; null hors bateau au m³. */
    BigDecimal pricePerM3,
    /** Borne haute de l'arrivée estimée (bateau : fourchette) ; null sinon. */
    LocalDate arrivalDateMax
) {

    /** Ancienne forme (avant les modes de transport) : champs de mode et de volume à null. */
    public AdminAnnouncementListItemResponse(UUID id, String status, String travelerName, String corridor,
                                             LocalDate departureDate, BigDecimal availableKg, BigDecimal pricePerKg,
                                             String currency, UUID tripGroupId, Integer tripLegIndex,
                                             Integer tripLegCount) {
        this(id, status, travelerName, corridor, departureDate, availableKg, pricePerKg, currency,
                tripGroupId, tripLegIndex, tripLegCount, null, null, null, null, null);
    }

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
                grouped ? tripLegCount : null,
                a.getTransportMode() != null ? a.getTransportMode().name() : null,
                a.getVolumeCapacityM3(), a.getAvailableVolumeM3(), a.getPricePerM3(),
                a.getArrivalDateMax());
    }
}
