package com.yadony.api.common;

import java.math.BigDecimal;

/**
 * Borne la taille des nombres décimaux reçus du client, avant tout calcul.
 *
 * <p>{@code new BigDecimal("1e10000000")} se lit instantanément (un chiffre, échelle
 * -10 000 000), mais le premier {@code setScale} ou {@code longValueExact} doit
 * matérialiser 10^10 000 000 : environ 24 s de CPU par requête (mesuré le 28/10), et
 * davantage de mémoire au-delà. {@code @DecimalMin}/{@code @DecimalMax} ne protègent pas :
 * ils comparent sans calculer. Le contrôle se fait donc sur {@code precision()} et
 * {@code scale()}, qui ne coûtent rien.
 *
 * <p>Les bornes laissent une large marge à tous les montants, poids, taux et
 * coordonnées réels : 15 chiffres avant la virgule (≈ 10^15), 18 après.
 */
public final class BoundedDecimals {

    public static final int MAX_INTEGER_DIGITS = 15;
    public static final int MAX_FRACTION_DIGITS = 18;

    private BoundedDecimals() {}

    public static boolean isBounded(BigDecimal value) {
        if (value == null) {
            return true;
        }
        long integerDigits = (long) value.precision() - value.scale();
        return value.scale() <= MAX_FRACTION_DIGITS && integerDigits <= MAX_INTEGER_DIGITS;
    }
}
