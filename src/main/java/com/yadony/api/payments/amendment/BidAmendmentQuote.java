package com.yadony.api.payments.amendment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Prix d'une modification, dans la devise du colis.
 *
 * @param net          net voyageur ajouté (kilos + articles), à l'unité mineure de la devise.
 * @param commission   commission différentielle {@code C(N + S) − C(N)}.
 * @param gross        {@code net + commission} : seul montant que l'expéditeur voit.
 * @param rateSnapshot taux effectif {@code commission / net}.
 * @param gridId       grille de commission figée du colis, null pour un colis hérité.
 * @param lines        articles de grille demandés, avec le prix unitaire net du trajet.
 */
public record BidAmendmentQuote(BigDecimal net, BigDecimal commission, BigDecimal gross,
                                BigDecimal rateSnapshot, UUID gridId, List<Line> lines) {

    public record Line(UUID gridItemId, String label, BigDecimal unitNet, int quantity) {
    }
}
