package com.yadony.api.payments.currency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ExchangeRateProperties.class)
public class FxRateConfig {

    @Bean
    public Cache<String, BigDecimal> fxRateCache(ExchangeRateProperties properties) {
        return Caffeine.newBuilder()
                .maximumSize(32)
                .expireAfterWrite(Duration.ofSeconds(properties.cacheTtlSeconds()))
                .build();
    }

    @Bean
    public FxRateProvider fxRateProvider(ExchangeRateProperties properties) {
        return new ConfiguredFxRateProvider(properties);
    }
}
