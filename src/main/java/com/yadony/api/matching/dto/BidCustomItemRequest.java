package com.yadony.api.matching.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Article hors grille : décrit ET chiffré par l'expéditeur, le voyageur ne l'a jamais tarifé.
 *  {@code amountEur} est le montant UNITAIRE. */
public record BidCustomItemRequest(
        @NotBlank(message = "Le libellé de l'article est obligatoire")
        @Size(max = 100, message = "Le libellé ne peut pas dépasser 100 caractères")
        String label,

        @Min(value = 1, message = "La quantité minimum est 1")
        @Max(value = 99, message = "La quantité maximum est 99")
        int quantity,

        @NotNull(message = "Le montant de l'article est obligatoire")
        @DecimalMin(value = "0.01", message = "Le montant minimum est 0,01")
        BigDecimal amountEur
) {}
