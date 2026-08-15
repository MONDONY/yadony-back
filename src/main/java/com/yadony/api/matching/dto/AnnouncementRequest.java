package com.yadony.api.matching.dto;

import com.yadony.api.payments.cash.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

public record AnnouncementRequest(
        @NotBlank(message = "La ville de départ est obligatoire")
        String departureCity,

        @NotBlank(message = "La ville d'arrivée est obligatoire")
        String arrivalCity,

        @NotNull(message = "La date de départ est obligatoire")
        @FutureOrPresent(message = "La date de départ ne peut pas être dans le passé")
        LocalDate departureDate,

        @NotNull(message = "L'heure de départ est obligatoire")
        @JsonFormat(pattern = "HH:mm")
        LocalTime departureTime,

        @JsonFormat(pattern = "HH:mm")
        LocalTime arrivalTime,

        @Valid @NotNull(message = "L'adresse de remise est obligatoire")
        AddressDto pickupAddress,

        @Valid @NotNull(message = "L'adresse de récupération est obligatoire")
        AddressDto deliveryAddress,

        @NotNull(message = "La capacité disponible est obligatoire")
        @DecimalMin(value = "1.0", message = "La capacité doit être d'au moins 1 kg")
        BigDecimal availableKg,

        // Nullable en mode MIXED (grille seule) ; validé côté service si mode KG
        @DecimalMin(value = "0.0", message = "Le prix ne peut pas être négatif")
        BigDecimal pricePerKg,

        @NotNull(message = "Le mode de transport est obligatoire")
        com.yadony.api.matching.TransportMode transportMode,

        @Size(max = 500, message = "La note ne peut pas dépasser 500 caractères")
        String description,

        List<String> acceptedContentTypes,

        List<String> refusedTypes,

        Set<PaymentMethod> acceptedPaymentMethods,

        com.yadony.api.matching.CapacityUnit capacityUnit,

        com.yadony.api.matching.PricingMode pricingMode,

        @Size(max = 2, message = "Le code pays de départ doit faire 2 caractères")
        String departureCountryCode,

        @Size(max = 2, message = "Le code pays d'arrivée doit faire 2 caractères")
        String arrivalCountryCode,

        // Date limite de dépôt — obligatoire (validée dans AnnouncementService).
        // Pas de @JsonFormat : reçoit un ISO-8601 (ex "2026-06-14T18:00:00.000Z").
        LocalDateTime handoverDeadline,

        // Brouillon : si true, l'annonce est créée en statut DRAFT (skip KYC + limite mensuelle,
        // soumise au quota de brouillons — cf AnnouncementService.createAnnouncement).
        Boolean saveAsDraft
) {
    public boolean isDraft() {
        return Boolean.TRUE.equals(saveAsDraft);
    }
}
