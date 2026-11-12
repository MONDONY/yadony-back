package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Bornes du prix net d'un article de grille tarifaire saisi par le voyageur, dans sa
 * devise : 5 € au moins (3 280 F CFA), 500 € au plus, mis à l'échelle au taux du
 * catalogue comme les bornes du prix au kilo ({@link PricePerKgBounds}). Refus 422
 * {@value #ERROR_CODE}, avec {@code reason}, {@code min}, {@code max} et {@code currency}.
 *
 * <p>Seule la saisie est jugée : les copies d'une grille dans une annonce et les
 * reconversions de devise passent par le taux administrable, un article converti peut
 * donc tomber de quelques centimes sous le plancher sans qu'on bloque la publication.
 */
public final class GridItemPriceBounds {

    public static final String ERROR_CODE = "price-grid/price-out-of-bounds";

    private GridItemPriceBounds() {
    }

    public static void assertWithinBounds(BigDecimal unitPriceNet, SupportedCurrency currency) {
        if (unitPriceNet == null) {
            return;
        }
        BigDecimal min = CurrencyBounds.minGridItemPrice(currency);
        BigDecimal max = CurrencyBounds.maxGridItemPrice(currency);
        if (unitPriceNet.compareTo(min) < 0) {
            throw refusal(PricePerKgBounds.REASON_TOO_LOW, min, max, currency, "problem.price-out-of-bounds.ce-prix-d-article-est-inferieur");
        }
        if (unitPriceNet.compareTo(max) > 0) {
            throw refusal(PricePerKgBounds.REASON_TOO_HIGH, min, max, currency, "problem.price-out-of-bounds.ce-prix-d-article-depasse-le");
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
