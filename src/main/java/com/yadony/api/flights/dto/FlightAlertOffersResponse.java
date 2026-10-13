package com.yadony.api.flights.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Vols trouvés pour une alerte prix ({@code GET /me/flight-alerts/{id}/offers}) : offres de la
 * période de l'alerte, avec son filtre direct et sa devise, au prix inférieur ou égal à
 * {@code maxPrice}, triées par prix croissant. Prix indicatifs (cache du partenaire, 30 min).
 *
 * @param searchPath chemin de recherche Aviasales du trajet, même format que
 *                   {@link FlightSearchResponse#searchPath()}, construit sur le premier jour de la
 *                   période ; {@code null} (absent) sans période de départ
 * @param checkedAt  instant de la lecture des prix
 */
public record FlightAlertOffersResponse(
        UUID alertId,
        String originCode,
        String destinationCode,
        String originLabel,
        String destinationLabel,
        boolean roundTrip,
        String currency,
        BigDecimal maxPrice,
        List<FlightOfferDto> offers,
        String searchPath,
        Instant checkedAt) {
}
