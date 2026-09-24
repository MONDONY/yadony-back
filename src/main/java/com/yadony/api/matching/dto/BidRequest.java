package com.yadony.api.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record BidRequest(
        @DecimalMin(value = "0.1", message = "{validation.bid.weight.min}")
        BigDecimal weightKg,  // nullable désormais (GRID mode = pas de poids)

        @NotBlank(message = "{validation.bid.description.required}")
        String description,

        @NotBlank(message = "{validation.bid.category.required}")
        // Multi-sélection jointe par virgule côté front — alignée sur BidCheckoutRequest
        // (500, cf. V171__unify_content_categories.sql pour le pourquoi).
        @Size(max = 500, message = "{validation.bid.category.max}")
        String contentCategory,

        @NotBlank(message = "{validation.bid.recipient-name.required}")
        String recipientName,

        @NotBlank(message = "{validation.bid.recipient-phone.required}")
        String recipientPhone,

        @NotNull(message = "{validation.bid.disclaimer.accepted}")
        Boolean disclaimerSigned,

        String paymentMethod,

        // Mobile Money fields — nullable; required only when paymentMethod is WAVE or ORANGE_MONEY
        @Pattern(regexp = "^\\+?[1-9]\\d{6,19}$", message = "{validation.phone.e164-expected}")
        String phoneNumber,

        @Size(max = 5, message = "{validation.bid.country-code.invalid}")
        String countryCode,

        /** Code promo optionnel (insensible à la casse) — validé et racheté au paiement. */
        String promoCode,

        @Size(max = 4, message = "Maximum 4 photos") List<String> photoKeys,

        @Valid List<BidGridItemRequest> gridItems  // peut être null ou vide — doit rester en DERNIER
) {}
