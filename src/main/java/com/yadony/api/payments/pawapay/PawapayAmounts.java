package com.yadony.api.payments.pawapay;

import com.yadony.api.payments.currency.SupportedCurrency;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** pawaPay attend une chaîne, sans décimale pour XOF/XAF (« 15000 »), deux sinon (« 12.50 »). */
public final class PawapayAmounts {

    private PawapayAmounts() {}

    public static String format(BigDecimal amount, String currencyCode) {
        int scale = SupportedCurrency.fromCodeOrDefault(currencyCode).minorUnit();
        return amount.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    public static BigDecimal round(BigDecimal amount, String currencyCode) {
        int scale = SupportedCurrency.fromCodeOrDefault(currencyCode).minorUnit();
        return amount.setScale(scale, RoundingMode.HALF_UP);
    }
}
