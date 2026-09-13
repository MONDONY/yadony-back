package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Poids livré et nombre de colis d'un trajet sur une période. */
public record KgSoldTripRow(
        UUID tripId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        long parcels,
        BigDecimal kg
) {}
