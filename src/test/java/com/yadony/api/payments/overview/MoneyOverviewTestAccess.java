package com.yadony.api.payments.overview;

import java.math.BigDecimal;

/** Accès de test à {@link MoneyStateResolver} (package-private) : le chiffre de « Mon argent ». */
public final class MoneyOverviewTestAccess {

    private MoneyOverviewTestAccess() {
    }

    public static BigDecimal amountOrZero(MoneyRow row) {
        return MoneyStateResolver.resolve(row).map(MoneyStateResolver.Resolution::amount).orElse(BigDecimal.ZERO);
    }
}
