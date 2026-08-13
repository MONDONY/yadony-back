package com.yadony.api.payments.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Bornes de validation des montants, exprimées dans la devise concernée.
 *
 * <p>Les annotations Bean Validation ne peuvent pas porter ces bornes : une
 * annotation est une constante de compilation, alors que la limite dépend de la
 * devise de l'objet validé. Les DTO ne gardent donc qu'un garde-fou large et la
 * vraie règle est appliquée ici, côté service, une fois la devise connue.
 *
 * <p>Toutes les valeurs de référence sont exprimées en euros puis mises à
 * l'échelle par {@link SupportedCurrency#unitsPerEur()}. On dimensionne un
 * formulaire, on ne convertit pas la saisie : le montant entré par l'utilisateur
 * reste intégralement dans sa devise d'origine.
 */
public final class CurrencyBounds {

    /** Prix maximum au kilo, en euros, avant mise à l'échelle. */
    private static final BigDecimal MAX_PRICE_PER_KG_EUR = new BigDecimal("500");

    /** Montant maximum d'une offre ou d'une contre-offre, en euros. */
    private static final BigDecimal MAX_NEGOTIATION_PRICE_EUR = new BigDecimal("500");

    /** Budget maximum d'une demande de colis, en euros. */
    private static final BigDecimal MAX_PACKAGE_BUDGET_EUR = new BigDecimal("560");

    /** Montant minimum d'un rechargement de portefeuille, en euros. */
    private static final BigDecimal MIN_TOPUP_EUR = new BigDecimal("1");

    private CurrencyBounds() {
    }

    /**
     * Met une borne exprimée en euros à l'échelle de la devise, puis l'arrondit au
     * nombre de décimales que cette devise autorise. L'arrondi suit le sens de la
     * borne : un plafond est arrondi vers le bas et un plancher vers le haut, pour
     * qu'une valeur acceptée ici le reste après enregistrement.
     */
    private static BigDecimal scale(BigDecimal amountInEur, SupportedCurrency currency, RoundingMode rounding) {
        return amountInEur
                .multiply(currency.unitsPerEur())
                .setScale(currency.minorUnit(), rounding);
    }

    public static BigDecimal maxPricePerKg(SupportedCurrency currency) {
        return scale(MAX_PRICE_PER_KG_EUR, currency, RoundingMode.DOWN);
    }

    public static BigDecimal maxNegotiationPrice(SupportedCurrency currency) {
        return scale(MAX_NEGOTIATION_PRICE_EUR, currency, RoundingMode.DOWN);
    }

    public static BigDecimal maxPackageBudget(SupportedCurrency currency) {
        return scale(MAX_PACKAGE_BUDGET_EUR, currency, RoundingMode.DOWN);
    }

    public static BigDecimal minTopup(SupportedCurrency currency) {
        return scale(MIN_TOPUP_EUR, currency, RoundingMode.UP);
    }

    /**
     * Exprime dans {@code currency} un barème défini en euros : récompense de
     * parrainage, seuil promotionnel. Le résultat garde la valeur du barème
     * (5 € valent environ 3 280 F CFA) et respecte la précision de la devise.
     *
     * <p>Réservé aux montants que la plateforme fixe elle-même. Un montant
     * échangé entre deux utilisateurs n'est jamais converti.
     */
    public static BigDecimal scaleFromEur(BigDecimal amountInEur, SupportedCurrency currency) {
        return scale(amountInEur, currency, RoundingMode.HALF_UP);
    }

    /**
     * Plus petit montant représentable dans la devise : 0,01 quand elle a des
     * centimes, 1 quand elle n'en a pas. Un plancher de 0,01 était impossible à
     * respecter en XOF, qui ne connaît pas de sous-unité.
     */
    public static BigDecimal smallestUnit(SupportedCurrency currency) {
        return BigDecimal.ONE.movePointLeft(currency.minorUnit());
    }
}
