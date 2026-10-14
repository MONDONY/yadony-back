package com.yadony.api.promo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Remise portée par un code promo validé, appliquée au taux de commission par
 * {@link com.yadony.api.common.CommissionRateResolver}.
 *
 * @param kind  sens de {@code value}.
 * @param value taux du code ({@code promo_codes.rate}).
 */
public record PromoDiscount(PromoDiscountKind kind, BigDecimal value) {

    /** Échelle de {@code bids.commission_rate} et {@code promo_redemptions.applied_rate}. */
    private static final int RATE_SCALE = 3;

    public static PromoDiscount points(BigDecimal value) {
        return new PromoDiscount(PromoDiscountKind.COMMISSION_POINTS, value);
    }

    public static PromoDiscount pricePercent(BigDecimal value) {
        return new PromoDiscount(PromoDiscountKind.PRICE_PERCENT, value);
    }

    /**
     * Taux de commission après remise, jamais négatif.
     *
     * <p>{@link PromoDiscountKind#COMMISSION_POINTS} : {@code base − value}.
     *
     * <p>{@link PromoDiscountKind#PRICE_PERCENT} : le prix payé vaut {@code net × (1 + taux)}
     * (PaymentService, MobileMoneyBidPricing, rail espèces). Une remise de {@code p} sur ce
     * prix, à net constant, donne {@code net × (1 + base) × (1 − p) = net × (1 + taux')},
     * soit {@code taux' = base − p × (1 + base)}. Plancher 0 : la remise est plafonnée à la
     * commission, le net du voyageur reste entier. La réduction est arrondie au millième
     * (échelle de {@code bids.commission_rate}, relu tel quel aux réessais de paiement).
     */
    public BigDecimal applyTo(BigDecimal baseRate) {
        BigDecimal reduction = kind == PromoDiscountKind.PRICE_PERCENT
                ? value.multiply(BigDecimal.ONE.add(baseRate)).setScale(RATE_SCALE, RoundingMode.HALF_UP)
                : value;
        return baseRate.subtract(reduction).max(BigDecimal.ZERO);
    }
}
