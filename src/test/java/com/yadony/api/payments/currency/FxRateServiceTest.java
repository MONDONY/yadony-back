package com.yadony.api.payments.currency;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxRateServiceTest {

    private final ExchangeRateProperties properties = new ExchangeRateProperties(
            new BigDecimal("655.957"), new BigDecimal("655.957"), 300);

    @Test
    void eur_to_eur_is_identity_without_calling_provider() {
        AtomicInteger calls = new AtomicInteger();
        FxRateProvider provider = (source, target) -> {
            calls.incrementAndGet();
            return BigDecimal.TEN;
        };
        FxRateService service = new FxRateService(provider, properties, Caffeine.newBuilder().build());

        CurrencyAmount result = service.convert(new BigDecimal("35.00"), SupportedCurrency.EUR);

        assertThat(result.major()).isEqualByComparingTo("35.00");
        assertThat(result.currency()).isEqualTo(SupportedCurrency.EUR);
        assertThat(calls).hasValue(0);
    }

    @Test
    void converts_xof_using_configured_parity_and_zero_decimal_precision() {
        FxRateService service = new FxRateService((source, target) -> {
            throw new AssertionError("configured parity must not call provider");
        }, properties, Caffeine.newBuilder().build());

        CurrencyAmount result = service.convert(new BigDecimal("10.00"), SupportedCurrency.XOF);

        assertThat(result.major()).isEqualByComparingTo("6560");
        assertThat(result.minor()).isEqualTo(6560L);
    }

    @Test
    void converts_cad_with_provider_rate_and_rounds_to_two_decimals() {
        FxRateService service = new FxRateService(
                (source, target) -> new BigDecimal("1.47"), properties, Caffeine.newBuilder().build());

        CurrencyAmount result = service.convert(new BigDecimal("10.00"), SupportedCurrency.CAD);

        assertThat(result.major()).isEqualByComparingTo("14.70");
        assertThat(result.minor()).isEqualTo(1470L);
    }

    @Test
    void caches_a_rate_for_the_same_target_currency() {
        AtomicInteger calls = new AtomicInteger();
        FxRateService service = new FxRateService(
                (source, target) -> {
                    calls.incrementAndGet();
                    return new BigDecimal("1.47");
                }, properties, Caffeine.newBuilder().build());

        service.convert(new BigDecimal("10.00"), SupportedCurrency.CAD);
        service.convert(new BigDecimal("20.00"), SupportedCurrency.CAD);

        assertThat(calls).hasValue(1);
    }

    @Test
    void maps_provider_failure_to_exchange_rate_unavailable() {
        FxRateService service = new FxRateService(
                (source, target) -> { throw new IllegalStateException("provider down"); },
                properties,
                Caffeine.newBuilder().build());

        assertThatThrownBy(() -> service.convert(new BigDecimal("10.00"), SupportedCurrency.CAD))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(error -> {
                    YadonyBusinessException exception = (YadonyBusinessException) error;
                    assertThat(exception.getErrorCode()).isEqualTo("exchange-rate-unavailable");
                });
    }

    @Test
    void configured_provider_supplies_major_local_currencies_without_network() {
        ExchangeRateProperties configured = new ExchangeRateProperties(
                new BigDecimal("655.957"), new BigDecimal("655.957"), 300,
                Map.of("cad", new BigDecimal("1.47")));
        FxRateService service = new FxRateService(
                new ConfiguredFxRateProvider(configured), configured, Caffeine.newBuilder().build());

        assertThat(service.convert(new BigDecimal("10.00"), SupportedCurrency.CAD).major())
                .isEqualByComparingTo("14.70");
    }
}
