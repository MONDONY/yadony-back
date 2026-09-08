package com.yadony.api.payments.pawapay;

import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import java.math.BigDecimal;

/**
 * pawaPay attend une chaîne, sans décimale pour XOF/XAF (« 15000 »), deux sinon (« 12.50 »).
 * L'arrondi à l'unité mineure est celui de {@link CurrencyAmount}, la seule règle monétaire du
 * dépôt — jamais une seconde arithmétique propre au rail.
 */
public final class PawapayAmounts {

    private PawapayAmounts() {}

    public static String format(BigDecimal amount, String currencyCode) {
        return round(amount, currencyCode).toPlainString();
    }

    public static BigDecimal round(BigDecimal amount, String currencyCode) {
        return CurrencyAmount.of(amount, SupportedCurrency.fromCodeOrDefault(currencyCode)).major();
    }
}
