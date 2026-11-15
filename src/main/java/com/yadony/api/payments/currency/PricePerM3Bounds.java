package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Refus d'un prix au m³ de trajet bateau hors des bornes dures réglées par l'administrateur (lot 4 bateau), sur
 * le modèle de {@link PricePerKgBounds} : bornes en euros mises à l'échelle de la devise du trajet (plancher
 * arrondi vers le haut, plafond vers le bas), 422 {@code price-per-m3-out-of-bounds} avec les propriétés
 * {@code reason} ({@code too-low} ou {@code too-high}), {@code min}, {@code max} et {@code currency}. Code distinct
 * de {@code price-out-of-bounds} : les applications publiées lisent celui-ci comme un prix au kilo.
 *
 * <p>Un prix absent n'est pas jugé ici : un trajet au kilo n'a pas de prix au m³.
 */
public final class PricePerM3Bounds {

    public static final String ERROR_CODE = "price-per-m3-out-of-bounds";

    private PricePerM3Bounds() {
    }

    public static void assertWithinBounds(BigDecimal pricePerM3, SupportedCurrency currency,
                                          BigDecimal hardMinEur, BigDecimal hardMaxEur) {
        if (pricePerM3 == null) {
            return;
        }
        BigDecimal min = CurrencyBounds.floorFromEur(hardMinEur, currency);
        BigDecimal max = CurrencyBounds.ceilingFromEur(hardMaxEur, currency);
        if (pricePerM3.compareTo(min) < 0) {
            throw refusal(PricePerKgBounds.REASON_TOO_LOW, min, max, currency,
                    "problem.price-per-m3-out-of-bounds.too-low");
        }
        if (pricePerM3.compareTo(max) > 0) {
            throw refusal(PricePerKgBounds.REASON_TOO_HIGH, min, max, currency,
                    "problem.price-per-m3-out-of-bounds.too-high");
        }
    }

    private static YadonyBusinessException refusal(String reason, BigDecimal min, BigDecimal max,
                                                   SupportedCurrency currency, String key) {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, ERROR_CODE, key,
                Map.of(
                        "reason", reason,
                        "min", min,
                        "max", max,
                        "currency", currency.code().toUpperCase(Locale.ROOT)));
    }
}
