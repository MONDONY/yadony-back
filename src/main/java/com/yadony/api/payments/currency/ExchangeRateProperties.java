package com.yadony.api.payments.currency;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "yadony.currency.fx")
public record ExchangeRateProperties(
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal xofPerEur,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal xafPerEur,
        @Min(1) int cacheTtlSeconds,
        Map<String, BigDecimal> rates) {

    @ConstructorBinding
    public ExchangeRateProperties {
    }

    public ExchangeRateProperties(BigDecimal xofPerEur, BigDecimal xafPerEur, int cacheTtlSeconds) {
        this(xofPerEur, xafPerEur, cacheTtlSeconds, Map.of());
    }
}
