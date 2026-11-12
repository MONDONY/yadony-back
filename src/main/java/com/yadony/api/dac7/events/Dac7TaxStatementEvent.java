package com.yadony.api.dac7.events;

import java.util.List;
import java.util.UUID;

/**
 * Récapitulatif annuel DAC7 d'un voyageur déclaré (art. 1649 ter D, III du CGI), décidé par l'équipe et réservé une
 * seule fois par voyageur et par année. Montants tels que transmis : entiers, dans la devise {@code currency}
 * (celle des paiements sans conversion, ou l'euro). Aucun NIF, aucune identité.
 */
public record Dac7TaxStatementEvent(UUID userId, int year, List<Quarter> quarters, String currency) {

    /** Compatibilité : montants en euros. */
    public Dac7TaxStatementEvent(UUID userId, int year, List<Quarter> quarters) {
        this(userId, year, quarters, "EUR");
    }

    /** {@code netEur} et {@code feesEur} : montants entiers dans la devise de l'événement (nom gardé). */
    public record Quarter(int quarter, long netEur, int operations, long feesEur) {
    }
}
