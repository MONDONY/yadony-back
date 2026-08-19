package com.yadony.api.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Les filtres qu'une phrase peut renseigner. Tous les champs sont nullable : seuls
 * ceux effectivement reconnus sont sérialisés. Les noms correspondent exactement aux
 * paramètres de {@code GET /announcements}, pour que le client les applique sans
 * table de correspondance.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedFilters(
        String departureCity,
        String arrivalCity,
        LocalDate departureDateFrom,
        LocalDate departureDateTo,
        BigDecimal minAvailableKg,
        BigDecimal maxWeight,
        BigDecimal maxPricePerKg,
        BigDecimal minRating,
        Boolean weekendOnly,
        Boolean urgent,
        Boolean kiloProOnly,
        Boolean kycVerifiedOnly,
        String transportMode,
        String contentType
) {}
