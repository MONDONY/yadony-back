package com.yadony.api.dac7.events;

import java.util.List;
import java.util.UUID;

/**
 * Récapitulatif annuel DAC7 d'un voyageur déclaré (art. 1649 ter D, III du CGI), décidé par l'équipe et réservé une
 * seule fois par voyageur et par année. Montants tels que transmis : euros entiers. Aucun NIF, aucune identité.
 */
public record Dac7TaxStatementEvent(UUID userId, int year, List<Quarter> quarters) {

    public record Quarter(int quarter, long netEur, int operations, long feesEur) {
    }
}
