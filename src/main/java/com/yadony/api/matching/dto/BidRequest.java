package com.yadony.api.matching.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record BidRequest(
        @DecimalMin(value = "0.1", message = "{validation.bid.weight.min}")
        // Maximum technique (NUMERIC(8,2), V380) : en kilo libre rien d'autre ne borne le poids.
        @DecimalMax(value = "999999.99", message = "{validation.bid.weight.max}")
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

        @Size(max = 4, message = "{validation.bid.photos.max}") List<String> photoKeys,

        @Valid List<BidGridItemRequest> gridItems,  // peut être null ou vide

        /**
         * Taille du colis (S, M, L, XL), facultative : une ancienne app n'en envoie pas, la réservation
         * est alors acceptée comme avant. Contrôlée contre la taille maximale du trajet
         * ({@code ParcelFitRules.assertFits}).
         */
        @Pattern(regexp = "S|M|L|XL", message = "{validation.request.item-size.pattern}")
        String itemSize,

        /**
         * Dimensions du colis (cm, 1 à 400), obligatoires sur un trajet bateau vendu au m³ : le volume est
         * calculé par le serveur ({@code ParcelFitRules.resolveBidVolume}), jamais envoyé. Ignorées ailleurs.
         * Doivent rester en DERNIER.
         */
        @Min(1) @Max(400) Integer lengthCm,
        @Min(1) @Max(400) Integer widthCm,
        @Min(1) @Max(400) Integer heightCm
) {
    /** Constructeur de compatibilité (sans dimensions) : la forme d'avant le bateau au m³. */
    public BidRequest(BigDecimal weightKg, String description, String contentCategory,
                      String recipientName, String recipientPhone, Boolean disclaimerSigned,
                      String paymentMethod, String phoneNumber, String countryCode, String promoCode,
                      List<String> photoKeys, List<BidGridItemRequest> gridItems, String itemSize) {
        this(weightKg, description, contentCategory, recipientName, recipientPhone, disclaimerSigned,
                paymentMethod, phoneNumber, countryCode, promoCode, photoKeys, gridItems, itemSize,
                null, null, null);
    }

    /** Constructeur de compatibilité (sans taille) : la forme d'avant le contrôle de taille. */
    public BidRequest(BigDecimal weightKg, String description, String contentCategory,
                      String recipientName, String recipientPhone, Boolean disclaimerSigned,
                      String paymentMethod, String phoneNumber, String countryCode, String promoCode,
                      List<String> photoKeys, List<BidGridItemRequest> gridItems) {
        this(weightKg, description, contentCategory, recipientName, recipientPhone, disclaimerSigned,
                paymentMethod, phoneNumber, countryCode, promoCode, photoKeys, gridItems, (String) null);
    }
}
