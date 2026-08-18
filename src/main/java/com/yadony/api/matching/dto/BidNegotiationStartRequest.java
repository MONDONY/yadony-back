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
        @DecimalMin(value = "0.1", message = "Le poids minimum est 0.1 kg")
        BigDecimal weightKg,

        @NotBlank(message = "La description du contenu est obligatoire")
        String description,

        @NotBlank(message = "La catégorie est obligatoire")
        @Size(max = 500, message = "La catégorie ne peut pas dépasser 500 caractères")
        String contentCategory,

        @NotBlank(message = "Le prénom et nom du destinataire sont obligatoires")
        String recipientName,

        @NotBlank(message = "Le numéro de téléphone du destinataire est obligatoire")
        String recipientPhone,

        @NotNull(message = "Le disclaimer légal doit être accepté")
        Boolean disclaimerSigned,

        String paymentMethod,

        @Pattern(regexp = "^\\+?[1-9]\\d{6,19}$", message = "Numéro de téléphone invalide (format E.164 attendu)")
        String phoneNumber,

        String countryCode,

        @Size(max = 4, message = "Maximum 4 photos")
        List<String> photoKeys,

        @NotNull(message = "Le montant proposé est obligatoire")
        @DecimalMin(value = "0.01", message = "Le montant minimum est 0,01")
        @DecimalMax(value = "1000000", message = "Le montant maximum est 1 000 000")
        BigDecimal proposedTotalEur,

        @Size(max = 10, message = "Maximum 10 articles hors grille")
        @Valid List<BidCustomItemRequest> customItems,

        @Valid List<BidGridItemRequest> gridItems
) {}
