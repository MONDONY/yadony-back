package com.yadony.api.payments.currency;

import com.github.benmanes.caffeine.cache.Cache;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

@Service
public class FxRateService {

    private static final String EUR = SupportedCurrency.EUR.code();

    private final FxRateProvider provider;
    private final ExchangeRateProperties properties;
    private final Cache<String, BigDecimal> rates;

    public FxRateService(FxRateProvider provider,
                         ExchangeRateProperties properties,
                         Cache<String, BigDecimal> rates) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.rates = Objects.requireNonNull(rates, "rates");
    }

    public CurrencyAmount convert(BigDecimal amountEur, SupportedCurrency target) {
        Objects.requireNonNull(amountEur, "amountEur");
        Objects.requireNonNull(target, "target");

        BigDecimal rate = rateFor(target);
        return CurrencyAmount.of(amountEur.multiply(rate), target);
    }

    public CurrencyAmount convertToEur(BigDecimal amount, SupportedCurrency source) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(source, "source");
        BigDecimal rate = rateFor(source);
        return CurrencyAmount.of(amount.divide(rate, 10, java.math.RoundingMode.HALF_UP), SupportedCurrency.EUR);
    }

    private BigDecimal rateFor(SupportedCurrency target) {
        if (target == SupportedCurrency.EUR) {
            return BigDecimal.ONE;
        }

        String targetCode = target.code();
        if (target == SupportedCurrency.XOF) {
            return properties.xofPerEur();
        }
        if (target == SupportedCurrency.XAF) {
            return properties.xafPerEur();
        }

        String cacheKey = EUR + "->" + targetCode;
        BigDecimal cached = rates.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            BigDecimal rate = provider.rate(EUR, targetCode);
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalStateException("Invalid exchange rate");
            }
            rates.put(cacheKey, rate);
            return rate;
        } catch (RuntimeException exception) {
            throw new YadonyBusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "exchange-rate-unavailable",
                    "Exchange rate unavailable",
                    "The exchange rate provider is temporarily unavailable.");
        }
    }
}
