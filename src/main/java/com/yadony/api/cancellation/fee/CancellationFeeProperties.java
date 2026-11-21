package com.yadony.api.cancellation.fee;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Frais retenus sur le remboursement d'un expéditeur qui annule : interrupteur, date d'effet et barème.
 *
 * @param enabled       interrupteur : éteint sauf {@code true} explicite ({@code YADONY_CANCELLATION_FEE_ENABLED}). À
 *                      n'allumer qu'une fois l'app qui montre les frais publiée et les CGU à jour. L'éteindre ne fait
 *                      que cesser d'en enregistrer de nouveaux : une retenue déjà due ou réglée suit son cours
 *                      (remboursement partiel, versement au voyageur), l'argent promis n'est jamais abandonné.
 * @param effectiveFrom date d'effet (instant ISO-8601, {@code YADONY_CANCELLATION_FEE_EFFECTIVE_FROM}) : seuls les colis
 *                      CRÉÉS à cet instant ou après peuvent porter des frais, un colis plus ancien a été payé sous des
 *                      CGU sans frais. Allumé sans date d'effet, l'interrupteur reste éteint (avertissement au démarrage,
 *                      {@link CancellationFeeEligibility}).
 * @param capEur        plafond du frais par annulation, en euros (30 par défaut), jamais négatif.
 * @param tiers         paliers, lus du plus lointain au plus proche du départ (triés ici par
 *                      {@code minDaysBefore} décroissant, chaque seuil unique et positif, chaque taux dans [0, 1]). Le
 *                      temps restant est mesuré en heures entières avant le départ ; un palier s'applique quand il reste
 *                      <b>au moins</b> {@code minDaysBefore * 24} heures, sauf le palier le plus lointain (fenêtre
 *                      gratuite) qui exige <b>strictement plus</b> : avec {@code 5 -> 0, 3 -> 0.10, 1 -> 0.20, 0 -> 0.30},
 *                      plus de 5 jours = 0 %, de 3 à 5 jours inclus = 10 %, de 1 à moins de 3 jours = 20 %, moins de
 *                      24 h ou après le départ = 30 %.
 */
@ConfigurationProperties(prefix = "yadony.cancellation-fee")
public record CancellationFeeProperties(boolean enabled, Instant effectiveFrom, BigDecimal capEur, List<Tier> tiers) {

    private static final String PREFIX = "yadony.cancellation-fee";

    /** @param minDaysBefore seuil du palier en jours avant le départ ; @param rate taux (0.10 = 10 %). */
    public record Tier(int minDaysBefore, BigDecimal rate) {}

    /**
     * Barème validé à la liaison : une valeur invalide lève {@link IllegalArgumentException}, et l'application refuse de
     * démarrer plutôt que de retenir un taux hors de [0, 1] ou un plafond négatif.
     */
    @ConstructorBinding
    public CancellationFeeProperties {
        if (capEur == null) capEur = new BigDecimal("30");
        if (capEur.signum() < 0) {
            throw new IllegalArgumentException(PREFIX + ".cap-eur : plafond négatif refusé (valeur reçue : "
                    + capEur.toPlainString() + ")");
        }
        tiers = tiers == null || tiers.isEmpty()
                ? List.of(new Tier(5, BigDecimal.ZERO), new Tier(3, new BigDecimal("0.10")),
                        new Tier(1, new BigDecimal("0.20")), new Tier(0, new BigDecimal("0.30")))
                : tiers.stream().sorted(Comparator.comparingInt(Tier::minDaysBefore).reversed()).toList();
        validate(tiers);
    }

    /** Barème seul, interrupteur éteint : calculs et tests qui ne dépendent pas de l'activation. */
    public CancellationFeeProperties(BigDecimal capEur, List<Tier> tiers) {
        this(false, null, capEur, tiers);
    }

    private static void validate(List<Tier> tiers) {
        Set<Integer> seen = new HashSet<>();
        for (Tier tier : tiers) {
            if (tier.rate() == null || tier.rate().signum() < 0 || tier.rate().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException(PREFIX + ".tiers : rate doit être compris entre 0 et 1 (palier "
                        + tier.minDaysBefore() + " j, valeur reçue : "
                        + (tier.rate() == null ? "absente" : tier.rate().toPlainString()) + ")");
            }
            if (tier.minDaysBefore() < 0) {
                throw new IllegalArgumentException(PREFIX + ".tiers : min-days-before négatif refusé (valeur reçue : "
                        + tier.minDaysBefore() + ")");
            }
            if (!seen.add(tier.minDaysBefore())) {
                throw new IllegalArgumentException(PREFIX + ".tiers : min-days-before " + tier.minDaysBefore()
                        + " en double, chaque palier doit avoir son propre seuil");
            }
        }
    }
}
