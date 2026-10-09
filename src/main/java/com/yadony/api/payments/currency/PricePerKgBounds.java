package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Refus unique d'un prix au kilo hors des bornes de sa devise (FLUTTER-GK), partagé par
 * le trajet simple, chaque étape d'un voyage, la récurrence et le modèle de trajet.
 *
 * <p>Le refus est un 422 dont le code reste {@code price-out-of-bounds} (ou la variante
 * préfixée du modèle) pour le plancher comme pour le plafond : les clients déjà publiés
 * reconnaissent ce code. Les propriétés {@code min}, {@code max}, {@code currency} et
 * {@code reason} ({@code too-low} ou {@code too-high}) permettent d'afficher la borne
 * franchie dans la devise de la saisie.
 *
 * <p>Un prix absent ou nul n'est pas jugé ici : en tarification au kilo il est refusé
 * comme prix manquant, et en mode grille il signifie « pas de tarif au kilo ».
 */
public final class PricePerKgBounds {

    public static final String REASON_TOO_LOW = "too-low";
    public static final String REASON_TOO_HIGH = "too-high";

    private PricePerKgBounds() {
    }

    public static void assertWithinBounds(BigDecimal pricePerKg, SupportedCurrency currency, String errorCode) {
        if (pricePerKg == null || pricePerKg.signum() <= 0) {
            return;
        }
        BigDecimal min = CurrencyBounds.minPricePerKg(currency);
        BigDecimal max = CurrencyBounds.maxPricePerKg(currency);
        if (pricePerKg.compareTo(min) < 0) {
            throw refusal(errorCode, REASON_TOO_LOW, min, max, currency,
                    "Ce prix au kilo est inférieur au minimum autorisé dans cette devise.");
        }
        if (pricePerKg.compareTo(max) > 0) {
            throw refusal(errorCode, REASON_TOO_HIGH, min, max, currency,
                    "Ce prix au kilo dépasse le plafond autorisé dans cette devise.");
        }
    }

    public static void assertWithinBounds(Double pricePerKg, SupportedCurrency currency, String errorCode) {
        assertWithinBounds(pricePerKg == null ? null : BigDecimal.valueOf(pricePerKg), currency, errorCode);
    }

    private static YadonyBusinessException refusal(String errorCode, String reason, BigDecimal min,
            BigDecimal max, SupportedCurrency currency, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, "Price Out Of Bounds",
                detail, Map.of(
                        "reason", reason,
                        "min", min,
                        "max", max,
                        "currency", currency.code().toUpperCase(Locale.ROOT)));
    }
}
