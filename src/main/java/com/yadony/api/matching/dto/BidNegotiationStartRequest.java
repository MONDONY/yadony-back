package com.yadony.api.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Première proposition d'un expéditeur sur un trajet négociable.
 *
 * <p>Volontairement distinct de {@code BidRequest} : ce dernier est un record
 * construit positionnellement dans toute la suite de tests, y ajouter des champs
 * casserait des centaines d'appels sans rapport avec la négociation.
 */
public record BidNegotiationStartRequest(
        @DecimalMin(value = "0.1", message = "{validation.bid.weight.min}")
        BigDecimal weightKg,

        @NotBlank(message = "{validation.bid.description.required}")
        String description,

        @NotBlank(message = "{validation.bid.category.required}")
        @Size(max = 500, message = "{validation.bid.category.max}")
        String contentCategory,

        @NotBlank(message = "{validation.bid.recipient-name.required}")
        String recipientName,

        @NotBlank(message = "{validation.bid.recipient-phone.required}")
        String recipientPhone,

        @NotNull(message = "{validation.bid.disclaimer.accepted}")
        Boolean disclaimerSigned,

        String paymentMethod,

        @Pattern(regexp = "^\\+?[1-9]\\d{6,19}$", message = "{validation.phone.e164-expected}")
        String phoneNumber,

        String countryCode,

        @Size(max = 4, message = "Maximum 4 photos")
        List<String> photoKeys,

        @NotNull(message = "{validation.amount.proposed.required}")
        @DecimalMin(value = "0.01", message = "{validation.amount.min-cent}")
        @DecimalMax(value = "1000000", message = "{validation.amount.max-million}")
        BigDecimal proposedTotalEur,

        @Size(max = 10, message = "Maximum 10 articles hors grille")
        @Valid List<BidCustomItemRequest> customItems,

        @Valid List<BidGridItemRequest> gridItems
) {}
