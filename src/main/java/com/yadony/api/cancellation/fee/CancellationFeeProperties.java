package com.yadony.api.cancellation.fee;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Barème des frais retenus sur le remboursement d'un expéditeur qui annule.
 *
 * @param capEur plafond du frais par annulation, en euros (30 par défaut).
 * @param tiers  paliers, lus du plus lointain au plus proche du départ (triés ici par
 *               {@code minDaysBefore} décroissant). Le temps restant est mesuré en heures
 *               entières avant le départ ; un palier s'applique quand il reste
 *               <b>au moins</b> {@code minDaysBefore * 24} heures, sauf le palier le plus
 *               lointain (fenêtre gratuite) qui exige <b>strictement plus</b> : avec
 *               {@code 5 -> 0, 3 -> 0.10, 1 -> 0.20, 0 -> 0.30}, plus de 5 jours = 0 %,
 *               de 3 à 5 jours inclus = 10 %, de 1 à moins de 3 jours = 20 %, moins de
 *               24 h ou après le départ = 30 %.
 */
@ConfigurationProperties(prefix = "yadony.cancellation-fee")
public record CancellationFeeProperties(BigDecimal capEur, List<Tier> tiers) {

    /** @param minDaysBefore seuil du palier en jours avant le départ ; @param rate taux (0.10 = 10 %). */
    public record Tier(int minDaysBefore, BigDecimal rate) {}

    public CancellationFeeProperties {
        if (capEur == null) capEur = new BigDecimal("30");
        tiers = tiers == null || tiers.isEmpty()
                ? List.of(new Tier(5, BigDecimal.ZERO), new Tier(3, new BigDecimal("0.10")),
                        new Tier(1, new BigDecimal("0.20")), new Tier(0, new BigDecimal("0.30")))
                : tiers.stream().sorted(Comparator.comparingInt(Tier::minDaysBefore).reversed()).toList();
    }
}
