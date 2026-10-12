package com.yadony.api.flights.dto;

import java.util.List;

/**
 * @param personalized {@code true} si les trajets viennent des corridors de l'utilisateur,
 *                     {@code false} si c'est la liste par défaut (aucun corridor connu)
 */
public record FlightDealsResponse(String currency, boolean personalized, List<FlightDealDto> deals) {
}
