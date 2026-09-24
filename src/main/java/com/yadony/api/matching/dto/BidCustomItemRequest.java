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
        @NotBlank(message = "{validation.item.label.required}")
        @Size(max = 100, message = "{validation.item.label.max}")
        String label,

        @Min(value = 1, message = "{validation.item.quantity.min}")
        @Max(value = 99, message = "{validation.item.quantity.max}")
        int quantity,

        @NotNull(message = "{validation.item.amount.required}")
        @DecimalMin(value = "0.01", message = "{validation.amount.min-cent}")
        BigDecimal amountEur
) {}
