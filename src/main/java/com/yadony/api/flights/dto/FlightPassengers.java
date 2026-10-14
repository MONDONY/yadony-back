package com.yadony.api.flights.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Composition du voyage choisie sur l'écran Billets. Les prix affichés restent ceux d'un adulte
 * (Data API Travelpayouts) : seul le lien de réservation Aviasales porte ces nombres.
 *
 * <p>Règles d'Aviasales : 1 à 9 adultes, au plus un bébé par adulte, 9 voyageurs au total.
 *
 * @param adults   adultes (12 ans et plus), 1 à 9
 * @param children enfants (2-11 ans), 0 à 8
 * @param infants  bébés (moins de 2 ans), 0 au nombre d'adultes
 */
public record FlightPassengers(
        @NotNull @Min(1) @Max(9) Integer adults,
        @NotNull @Min(0) @Max(8) Integer children,
        @NotNull @Min(0) @Max(9) Integer infants) {

    public static final int MAX_TOTAL = 9;

    @JsonIgnore
    @AssertTrue(message = "Au plus un bébé par adulte.")
    public boolean isInfantsWithinAdults() {
        return adults == null || infants == null || infants <= adults;
    }

    @JsonIgnore
    @AssertTrue(message = "9 voyageurs au plus.")
    public boolean isTotalWithinLimit() {
        return adults == null || children == null || infants == null || total() <= MAX_TOTAL;
    }

    /** Nombre total de voyageurs, bébés compris. */
    public int total() {
        return adults + children + infants;
    }

    /** Vrai pour un adulte seul : le lien d'origine convient tel quel. */
    @JsonIgnore
    public boolean isSingleAdult() {
        return adults == 1 && children == 0 && infants == 0;
    }
}
