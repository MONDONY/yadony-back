package com.yadony.api.payments.supplement;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Prix d'un supplément, à l'unité mineure de la devise du colis.
 *
 * @param net          net demandé par le voyageur.
 * @param commission   commission Yadony du supplément (la seule différence de commission).
 * @param gross        total payé par l'expéditeur, {@code net + commission}.
 * @param rateSnapshot taux effectif {@code commission / net}, borné comme les autres taux enregistrés.
 * @param gridId       grille figée du bid, null pour un bid hérité.
 */
public record BidSupplementQuote(BigDecimal net, BigDecimal commission, BigDecimal gross,
                                 BigDecimal rateSnapshot, UUID gridId) {
}
