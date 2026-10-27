package com.yadony.api.payments.currency;

import com.yadony.api.admin.AdminAlertEscalator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeRateFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private ExchangeRateRepository repository;
    private AdminAlertEscalator alerts;
    private ExchangeRateFreshness freshness;

    @BeforeEach
    void setUp() {
        repository = mock(ExchangeRateRepository.class);
        alerts = mock(AdminAlertEscalator.class);
        freshness = new ExchangeRateFreshness(repository, alerts, 48);
    }

    private void rateUpdated(String currency, Instant at) {
        ExchangeRateEntity entity = mock(ExchangeRateEntity.class);
        when(entity.getUpdatedAt()).thenReturn(at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
        when(entity.getUnitsPerEur()).thenReturn(new BigDecimal("1.08"));
        when(repository.findByCurrency(currency)).thenReturn(Optional.of(entity));
    }

    @Test
    void pegged_currencies_never_go_stale_and_are_never_read() {
        for (String code : new String[] {"EUR", "XOF", "XAF", " xof "}) {
            assertThat(freshness.isStale(code, NOW)).as(code).isFalse();
        }
        verifyNoInteractions(repository);
    }

    @Test
    void floating_rate_updated_this_morning_is_fresh() {
        rateUpdated("USD", NOW.minus(5, ChronoUnit.HOURS));

        assertThat(freshness.isStale("USD", NOW)).isFalse();
    }

    @Test
    void floating_rate_is_fresh_at_exactly_the_limit_and_stale_one_second_after() {
        rateUpdated("USD", NOW.minus(48, ChronoUnit.HOURS));
        assertThat(freshness.isStale("USD", NOW)).isFalse();

        rateUpdated("GBP", NOW.minus(48, ChronoUnit.HOURS).minusSeconds(1));
        assertThat(freshness.isStale("GBP", NOW)).isTrue();
    }

    @Test
    void floating_rate_of_three_days_is_stale() {
        rateUpdated("CAD", NOW.minus(72, ChronoUnit.HOURS));

        assertThat(freshness.isStale("CAD", NOW)).isTrue();
    }

    @Test
    void a_missing_or_undated_floating_rate_is_stale() {
        when(repository.findByCurrency("CHF")).thenReturn(Optional.empty());
        assertThat(freshness.isStale("CHF", NOW)).isTrue();

        rateUpdated("USD", null);
        assertThat(freshness.isStale("USD", NOW)).isTrue();
    }

    @Test
    void the_limit_follows_the_configured_hours() {
        ExchangeRateFreshness strict = new ExchangeRateFreshness(repository, alerts, 6);
        rateUpdated("USD", NOW.minus(7, ChronoUnit.HOURS));

        assertThat(strict.isStale("USD", NOW)).isTrue();
        assertThat(freshness.isStale("USD", NOW)).isFalse();
    }

    @Test
    void alertStale_raisesOneAlertPerCurrency_withTheLimitInTheMessage() {
        freshness.alertStale(" usd ");

        verify(alerts).raiseOnce(eq("EXCHANGE_RATE_STALE_USD"), contains("48 h"), anyMap());
        verify(alerts, never()).raiseOnce(eq("EXCHANGE_RATE_STALE_EUR"), anyString(), anyMap());
    }

    @Test
    void alertStale_neverBlocksAChargeWhenTheAlertFails() {
        doThrow(new IllegalStateException("base d'alertes indisponible"))
                .when(alerts).raiseOnce(anyString(), anyString(), anyMap());

        freshness.alertStale("USD");

        verify(alerts).raiseOnce(eq("EXCHANGE_RATE_STALE_USD"), anyString(), eq(Map.of("currency", "USD", "maxAgeHours", 48L)));
    }
}
