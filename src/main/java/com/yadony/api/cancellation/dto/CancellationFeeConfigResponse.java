package com.yadony.api.cancellation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * État public des frais d'annulation ({@code GET /config/cancellation-fee}), lu par l'app pour choisir ses textes et
 * montrer le barème avant paiement. Ne contient que de la configuration : jamais un montant d'utilisateur.
 *
 * @param active        frais allumés ET date d'effet posée (même décision que
 *                      {@code CancellationFeeEligibility#active()})
 * @param effectiveFrom date d'effet (seuls les colis créés à cet instant ou après portent des frais) ; {@code null}
 *                      (champ présent, valeur nulle) quand {@code active} est faux
 * @param capEur        plafond du frais par annulation, en euros
 * @param tiers         paliers, du plus lointain au plus proche du départ. Un palier s'applique quand il reste AU MOINS
 *                      {@code minDaysBefore} jours avant le départ, sauf le premier (le plus lointain, fenêtre gratuite)
 *                      qui exige STRICTEMENT plus
 */
// ALWAYS : effectiveFrom reste présent (null) quand les frais sont éteints, la forme ne varie pas.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CancellationFeeConfigResponse(boolean active, Instant effectiveFrom, BigDecimal capEur, List<Tier> tiers) {

    /** @param minDaysBefore seuil en jours avant le départ ; @param rate taux de 0 à 1 (0.10 = 10 %). */
    public record Tier(int minDaysBefore, BigDecimal rate) {}
}
