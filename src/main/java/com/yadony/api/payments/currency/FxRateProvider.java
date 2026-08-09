package com.yadony.api.payments.currency;

import java.math.BigDecimal;

@FunctionalInterface
public interface FxRateProvider {
    BigDecimal rate(String source, String target);
}
