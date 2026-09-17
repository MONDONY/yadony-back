package com.yadony.api.payments.wallet;

import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Montant tel qu'on le MONTRE à l'utilisateur : « 1 000 000 F CFA », « 12,50 € ». À ne pas
 * confondre avec {@code PawapayAmounts.format}, qui rend la chaîne attendue par pawaPay
 * (« 1000000 », sans séparateur ni symbole) et n'a rien à faire dans un texte affiché.
 *
 * <p>Le nombre de décimales est celui de la devise ({@link SupportedCurrency#minorUnit()}) :
 * aucune pour XOF/XAF, deux sinon, avec le même arrondi monétaire que
 * {@link CurrencyAmount#of} — une seule règle d'arrondi dans le rail. Le séparateur de
 * milliers est une espace INSÉCABLE : sur un écran étroit, une espace ordinaire coupe le
 * montant en fin de ligne.
 */
public final class WalletAmountText {

    /** Espace insécable, séparateur de milliers et séparateur avant le symbole. */
    private static final char NBSP = ' ';

    private WalletAmountText() {}

    public static String format(BigDecimal amount, String currency) {
        SupportedCurrency supported = SupportedCurrency.fromCodeOrDefault(currency);
        BigDecimal rounded = CurrencyAmount.of(amount, supported).major();

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRENCH);
        symbols.setGroupingSeparator(NBSP);
        symbols.setDecimalSeparator(',');
        int minorUnit = supported.minorUnit();
        StringBuilder pattern = new StringBuilder("#,##0");
        if (minorUnit > 0) {
            pattern.append('.').append("0".repeat(minorUnit));
        }
        DecimalFormat format = new DecimalFormat(pattern.toString(), symbols);
        return format.format(rounded) + NBSP + supported.symbol();
    }
}
