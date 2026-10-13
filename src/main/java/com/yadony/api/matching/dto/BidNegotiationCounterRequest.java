package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Contre-offre d'un participant sur un fil de négociation de trajet.
 *
 * <p>Un seul montant, au choix (FLUTTER-NS) :
 * <ul>
 *   <li>{@code proposedTotalEur} : le BRUT, ce que l'expéditeur paiera (seul montant connu
 *       des anciens clients, et seul permis à l'expéditeur) ;</li>
 *   <li>{@code proposedNetEur} : le NET que le voyageur veut toucher, réservé au voyageur.
 *       Le serveur en déduit le brut avec le taux figé du fil.</li>
 * </ul>
 * Les deux ensemble : 422 {@code counter-amount-ambiguous}.
 */
public record BidNegotiationCounterRequest(
        @DecimalMin(value = "0.01", message = "{validation.amount.min-cent}")
        @DecimalMax(value = "1000000", message = "{validation.amount.max-million}")
        BigDecimal proposedTotalEur,

        @Size(max = 280, message = "{validation.negotiation.message.max}")
        String body,

        @DecimalMin(value = "0.01", message = "{validation.amount.min-cent}")
        @DecimalMax(value = "1000000", message = "{validation.amount.max-million}")
        BigDecimal proposedNetEur
) {
    /** Ancien contrat : brut seul. */
    public BidNegotiationCounterRequest(BigDecimal proposedTotalEur, String body) {
        this(proposedTotalEur, body, null);
    }

    /** Au moins un montant : même message que l'ancien {@code @NotNull} du brut. */
    @JsonIgnore
    @AssertTrue(message = "{validation.amount.proposed.required}")
    public boolean isAmountProvided() {
        return proposedTotalEur != null || proposedNetEur != null;
    }
}
