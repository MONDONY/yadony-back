package com.yadony.api.common.commission;

import com.yadony.api.payments.currency.SupportedCurrency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Grille de commission marginale datée : une même grille pour tous les rails et toutes les
 * devises, bornes saisies en euros, minimum propre à chaque devise.
 *
 * @param id            identifiant de la grille, figé sur le bid, le fil ou la demande.
 * @param effectiveFrom date d'effet (UTC).
 * @param tiers         tranches ; triées ici par borne croissante.
 * @param minimums      commission minimum par devise, dans l'unité de la devise.
 */
public record CommissionGrid(UUID id, LocalDateTime effectiveFrom, List<CommissionTier> tiers,
                             Map<SupportedCurrency, BigDecimal> minimums) {

    public CommissionGrid {
        List<CommissionTier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparing(CommissionTier::fromEur));
        tiers = List.copyOf(sorted);
        minimums = Map.copyOf(minimums);
    }

    /**
     * Commission de grille NON arrondie : {@code Σ taux_i × max(0, min(net, borne_sup_i) − borne_inf_i)}.
     * Les bornes sont converties par {@code × unitsPerEur} sans arrondi (65 595,7 XOF pour 100 €) :
     * l'unique arrondi a lieu à la fin du calcul, dans {@link CommissionCalculator}.
     */
    public BigDecimal gridCommission(BigDecimal net, SupportedCurrency c) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            BigDecimal lower = tiers.get(i).fromEur().multiply(c.unitsPerEur());
            if (net.compareTo(lower) <= 0) {
                break;
            }
            BigDecimal upper = i + 1 < tiers.size()
                    ? tiers.get(i + 1).fromEur().multiply(c.unitsPerEur())
                    : null;
            BigDecimal top = upper == null ? net : net.min(upper);
            total = total.add(tiers.get(i).rate().multiply(top.subtract(lower)));
        }
        return total;
    }

    /** Commission minimum de la devise. La validation de la grille garantit toutes les devises. */
    public BigDecimal minimum(SupportedCurrency c) {
        BigDecimal minimum = minimums.get(c);
        if (minimum == null) {
            throw new IllegalStateException("Grille de commission " + id + " sans minimum pour " + c);
        }
        return minimum;
    }
}
