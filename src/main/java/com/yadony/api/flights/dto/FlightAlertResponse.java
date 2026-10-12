package com.yadony.api.flights.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @param lastSeenPrice prix le plus bas trouvé à la dernière vérification, {@code null}
 *                      tant que l'alerte n'a pas encore été vérifiée
 */
public record FlightAlertResponse(
        UUID id,
        String originCode,
        String destinationCode,
        String originLabel,
        String destinationLabel,
        LocalDate departureFrom,
        LocalDate departureTo,
        LocalDate returnFrom,
        LocalDate returnTo,
        boolean directOnly,
        BigDecimal maxPrice,
        String currency,
        boolean active,
        BigDecimal lastSeenPrice,
        LocalDateTime lastCheckedAt,
        LocalDateTime createdAt) {
}
