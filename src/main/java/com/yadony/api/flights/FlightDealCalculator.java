package com.yadony.api.flights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Décide si le prix le plus bas d'un trajet est un « bon plan ».
 *
 * <p>Règle : le moins cher est au moins {@link #MIN_DISCOUNT_PERCENT} % sous la médiane des
 * prix observés sur le même trajet. La médiane plutôt que la moyenne : un seul billet
 * hors de prix (classe affaires, dernière minute) ne doit pas fabriquer une fausse
 * réduction. Sous {@link #MIN_SAMPLES} prix, la comparaison ne veut rien dire : pas de bon plan.
 */
final class FlightDealCalculator {

    static final int MIN_DISCOUNT_PERCENT = 20;
    static final int MIN_SAMPLES = 4;

    record Deal(TravelpayoutsPrice cheapest, BigDecimal medianPrice, int discountPercent) {
    }

    private FlightDealCalculator() {
    }

    static Optional<Deal> evaluate(List<TravelpayoutsPrice> prices) {
        List<TravelpayoutsPrice> valid = prices.stream()
                .filter(p -> p.price() != null && p.price().signum() > 0)
                .sorted(Comparator.comparing(TravelpayoutsPrice::price))
                .toList();
        if (valid.size() < MIN_SAMPLES) return Optional.empty();

        BigDecimal median = median(valid.stream().map(TravelpayoutsPrice::price).toList());
        TravelpayoutsPrice cheapest = valid.get(0);
        int discount = BigDecimal.ONE
                .subtract(cheapest.price().divide(median, 4, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        if (discount < MIN_DISCOUNT_PERCENT) return Optional.empty();
        return Optional.of(new Deal(cheapest, median.setScale(2, RoundingMode.HALF_UP), discount));
    }

    /** {@code sorted} doit être trié par ordre croissant. */
    static BigDecimal median(List<BigDecimal> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }
}
