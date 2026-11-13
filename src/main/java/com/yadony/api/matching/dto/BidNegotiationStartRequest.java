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

/**
 * Première proposition d'un expéditeur sur un trajet négociable.
 *
 * <p>Volontairement distinct de {@code BidRequest} : ce dernier est un record
 * construit positionnellement dans toute la suite de tests, y ajouter des champs
 * casserait des centaines d'appels sans rapport avec la négociation.
 */
public record BidNegotiationStartRequest(
        @DecimalMin(value = "0.1", message = "{validation.bid.weight.min}")
        // Maximum technique (NUMERIC(8,2), V380) : en kilo libre rien d'autre ne borne le poids.
        @DecimalMax(value = "999999.99", message = "{validation.bid.weight.max}")
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

        @Size(max = 4, message = "{validation.bid.photos.max}")
        List<String> photoKeys,

        @NotNull(message = "{validation.amount.proposed.required}")
        @DecimalMin(value = "0.01", message = "{validation.amount.min-cent}")
        @DecimalMax(value = "1000000", message = "{validation.amount.max-million}")
        BigDecimal proposedTotalEur,

        @Size(max = 10, message = "{validation.bid.custom-items.max}")
        @Valid List<BidCustomItemRequest> customItems,

        @Valid List<BidGridItemRequest> gridItems,

        /** Taille du colis (S, M, L, XL), facultative : absente = acceptée comme avant. */
        @Pattern(regexp = "S|M|L|XL", message = "{validation.request.item-size.pattern}")
        String itemSize,

        /** Dimensions du colis (cm), obligatoires sur un trajet bateau vendu au m³ (mêmes noms que l'offre). */
        @Min(1) @Max(400) Integer lengthCm,
        @Min(1) @Max(400) Integer widthCm,
        @Min(1) @Max(400) Integer heightCm
) {
    /** Constructeur de compatibilité (sans dimensions) : la forme d'avant le bateau au m³. */
    public BidNegotiationStartRequest(BigDecimal weightKg, String description, String contentCategory,
                                      String recipientName, String recipientPhone, Boolean disclaimerSigned,
                                      String paymentMethod, String phoneNumber, String countryCode,
                                      List<String> photoKeys, BigDecimal proposedTotalEur,
                                      List<BidCustomItemRequest> customItems,
                                      List<BidGridItemRequest> gridItems, String itemSize) {
        this(weightKg, description, contentCategory, recipientName, recipientPhone, disclaimerSigned,
                paymentMethod, phoneNumber, countryCode, photoKeys, proposedTotalEur, customItems,
                gridItems, itemSize, null, null, null);
    }

    /** Constructeur de compatibilité (sans taille) : la forme d'avant le contrôle de taille. */
    public BidNegotiationStartRequest(BigDecimal weightKg, String description, String contentCategory,
                                      String recipientName, String recipientPhone, Boolean disclaimerSigned,
                                      String paymentMethod, String phoneNumber, String countryCode,
                                      List<String> photoKeys, BigDecimal proposedTotalEur,
                                      List<BidCustomItemRequest> customItems,
                                      List<BidGridItemRequest> gridItems) {
        this(weightKg, description, contentCategory, recipientName, recipientPhone, disclaimerSigned,
                paymentMethod, phoneNumber, countryCode, photoKeys, proposedTotalEur, customItems,
                gridItems, (String) null);
    }
}
