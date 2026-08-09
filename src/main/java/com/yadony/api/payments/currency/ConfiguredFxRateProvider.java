package com.yadony.api.payments.currency;


import java.math.BigDecimal;
import java.util.Locale;

public class ConfiguredFxRateProvider implements FxRateProvider {

    private final ExchangeRateProperties properties;

    public ConfiguredFxRateProvider(ExchangeRateProperties properties) {
        this.properties = properties;
    }

    @Override
    public BigDecimal rate(String source, String target) {
        if (!"eur".equalsIgnoreCase(source)) {
            throw new IllegalStateException("Only EUR source is supported");
        }
        BigDecimal rate = properties.rates().get(target.toLowerCase(Locale.ROOT));
        if (rate == null) {
            throw new IllegalStateException("No configured FX rate for " + target);
        }
        return rate;
    }
}
