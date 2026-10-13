package com.yadony.api.flights.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @param lastSeenPrice prix le plus bas trouvé à la dernière vérification, {@code null}
 *                      tant que l'alerte n'a pas encore été vérifiée (même au-dessus du seuil)
 * @param matchCount    nombre d'offres de la dernière vérification (période de l'alerte, filtre
 *                      direct, devise) au prix inférieur ou égal à {@code maxPrice} ; {@code 0}
 *                      quand plus rien n'est sous le seuil ; {@code null} (absent du JSON) tant
 *                      que l'alerte n'a jamais été vérifiée
 * @param matchAirlineCodes jusqu'à trois codes IATA distincts de ces offres, par prix croissant ;
 *                      liste vide quand rien n'est sous le seuil ; {@code null} (absent) tant que
 *                      l'alerte n'a jamais été vérifiée
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
        LocalDateTime createdAt,
        Integer matchCount,
        List<String> matchAirlineCodes) {
}
