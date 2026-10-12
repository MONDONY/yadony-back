package com.yadony.api.flights.dto;

import java.math.BigDecimal;

/**
 * Un bon plan : l'offre la moins chère d'un trajet et son écart à la médiane des prix
 * observés. Affiché « −{discountPercent} % vs prix habituel », jamais comme une réduction
 * accordée par Yadony.
 */
public record FlightDealDto(FlightOfferDto offer, BigDecimal medianPrice, int discountPercent) {
}
