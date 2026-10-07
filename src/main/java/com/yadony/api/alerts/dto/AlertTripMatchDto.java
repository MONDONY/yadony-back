package com.yadony.api.alerts.dto;

import com.yadony.api.matching.TransportMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record AlertTripMatchDto(
        UUID announcementId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        UUID travelerId,
        String travelerName,
        String travelerInitials,
        double travelerRating,
        BigDecimal availableKg,
        BigDecimal pricePerKg,
        TransportMode transportMode,
        String photoUrl,
        String currency,
        /** Publication du trajet : sert à séparer « nouveaux » et « déjà vus » côté app. */
        LocalDateTime publishedAt,
        /**
         * Statut du trajet (ACTIVE, FULL) : un trajet public complet reste dans les
         * correspondances, l'app l'affiche « Complet ». Ajouté en dernier, lu en
         * optionnel côté app.
         */
        String status
) {}
