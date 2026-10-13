package com.yadony.api.flights.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Vols trouvés pour une alerte prix ({@code GET /me/flight-alerts/{id}/offers}) : offres de la
 * période de l'alerte, avec son filtre direct et sa devise, triées par prix croissant : au prix
 * inférieur ou égal à {@code maxPrice} par défaut ({@code scope=below}), sans filtre de prix avec
 * {@code scope=all}. Prix indicatifs (cache du partenaire, 30 min).
 *
 * @param searchPath chemin de recherche Aviasales du trajet, même format que
 *                   {@link FlightSearchResponse#searchPath()}, construit sur le premier jour de la
 *                   période ; {@code null} (absent) sans période de départ
 * @param checkedAt  instant de la lecture des prix
 * @param belowCount nombre d'offres de {@code offers} au prix inférieur ou égal à {@code maxPrice}
 *                   (égal à la taille de la liste avec {@code scope=below})
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
        Instant checkedAt,
        Integer belowCount) {
}
