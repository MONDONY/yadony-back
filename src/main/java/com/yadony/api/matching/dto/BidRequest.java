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
        @DecimalMin(value = "0.1", message = "Le poids minimum est 0.1 kg")
        BigDecimal weightKg,  // nullable désormais (GRID mode = pas de poids)

        @NotBlank(message = "La description du contenu est obligatoire")
        String description,

        @NotBlank(message = "La catégorie est obligatoire")
        // Multi-sélection jointe par virgule côté front — alignée sur BidCheckoutRequest
        // (500, cf. V171__unify_content_categories.sql pour le pourquoi).
        @Size(max = 500, message = "La catégorie ne peut pas dépasser 500 caractères")
        String contentCategory,

        @NotBlank(message = "Le prénom et nom du destinataire sont obligatoires")
        String recipientName,

        @NotBlank(message = "Le numéro de téléphone du destinataire est obligatoire")
        String recipientPhone,

        @NotNull(message = "Le disclaimer légal doit être accepté")
        Boolean disclaimerSigned,

        String paymentMethod,

        // Mobile Money fields — nullable; required only when paymentMethod is WAVE or ORANGE_MONEY
        @Pattern(regexp = "^\\+?[1-9]\\d{6,19}$", message = "Numéro de téléphone invalide (format E.164 attendu)")
        String phoneNumber,

        @Size(max = 5, message = "Code pays invalide")
        String countryCode,

        /** Code promo optionnel (insensible à la casse) — validé et racheté au paiement. */
        String promoCode,

        @Size(max = 4, message = "Maximum 4 photos") List<String> photoKeys,

        @Valid List<BidGridItemRequest> gridItems  // peut être null ou vide — doit rester en DERNIER
) {}
