package com.yadony.api.payments.wallet;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletEstimateServiceTest {

    private ExchangeRateService exchangeRateService;
    private WalletEstimateService service;

    @BeforeEach
    void setUp() {
        exchangeRateService = mock(ExchangeRateService.class);
        service = new WalletEstimateService(exchangeRateService);
    }

    private static WalletAccountEntity wallet(String currency, String balance) {
        WalletAccountEntity w = new WalletAccountEntity();
        w.setCurrency(currency);
        w.setBalance(new BigDecimal(balance));
        return w;
    }

    @Test
    void sumsEveryCurrencyConvertedIntoTheActiveOne() {
        when(exchangeRateService.convert(new BigDecimal("1.33"), "EUR", "EUR"))
                .thenReturn(new BigDecimal("1.33"));
        when(exchangeRateService.convert(new BigDecimal("10000"), "XOF", "EUR"))
                .thenReturn(new BigDecimal("15.24"));

        WalletEstimate estimate = service.estimate(
                List.of(wallet("EUR", "1.33"), wallet("XOF", "10000")), "EUR");

        assertThat(estimate.total()).isEqualByComparingTo("16.57");
        assertThat(estimate.complete()).isTrue();
        assertThat(estimate.inActiveByCurrency()).containsEntry("EUR", new BigDecimal("1.33"));
        assertThat(estimate.inActiveByCurrency()).containsEntry("XOF", new BigDecimal("15.24"));
    }

    @Test
    void excludesCurrencyWithoutRateAndFlagsPartialEstimate() {
        when(exchangeRateService.convert(new BigDecimal("1.33"), "EUR", "EUR"))
                .thenReturn(new BigDecimal("1.33"));
        when(exchangeRateService.convert(any(), eq("GBP"), eq("EUR")))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "exchange-rate-missing", "Exchange Rate Missing", "no rate", Map.of()));

        WalletEstimate estimate = service.estimate(
                List.of(wallet("EUR", "1.33"), wallet("GBP", "20.00")), "EUR");

        assertThat(estimate.total()).isEqualByComparingTo("1.33");
        assertThat(estimate.complete()).isFalse();
        assertThat(estimate.inActiveByCurrency()).containsEntry("EUR", new BigDecimal("1.33"));
        assertThat(estimate.inActiveByCurrency()).doesNotContainKey("GBP");
    }

    @Test
    void totalIsNullWhenNothingCouldBeConverted() {
        when(exchangeRateService.convert(any(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "exchange-rate-missing", "Exchange Rate Missing", "no rate", Map.of()));

        WalletEstimate estimate = service.estimate(List.of(wallet("GBP", "20.00")), "EUR");

        assertThat(estimate.total()).isNull();
        assertThat(estimate.complete()).isFalse();
        assertThat(estimate.inActiveByCurrency()).isEmpty();
    }

    @Test
    void rethrowsBusinessExceptionsOtherThanMissingRate() {
        when(exchangeRateService.convert(any(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "currency-unsupported", "Currency Unsupported", "no", Map.of()));

        assertThatThrownBy(() -> service.estimate(List.of(wallet("EUR", "1")), "EUR"))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("no");
    }

    @Test
    void emptyWalletsGiveZeroCompleteTotal() {
        WalletEstimate estimate = service.estimate(List.of(), "EUR");

        assertThat(estimate.total()).isEqualByComparingTo("0");
        assertThat(estimate.complete()).isTrue();
    }

    @Test
    void roundsTotalToActiveCurrencyDecimals() {
        // Devise active XOF (0 décimale) : 1,33 EUR = 872,42 XOF et 10 000 XOF.
        when(exchangeRateService.convert(new BigDecimal("1.33"), "EUR", "XOF"))
                .thenReturn(new BigDecimal("872"));
        when(exchangeRateService.convert(new BigDecimal("10000"), "XOF", "XOF"))
                .thenReturn(new BigDecimal("10000"));

        WalletEstimate estimate = service.estimate(
                List.of(wallet("EUR", "1.33"), wallet("XOF", "10000")), "XOF");

        assertThat(estimate.total()).isEqualByComparingTo("10872");
        assertThat(estimate.total().scale()).isZero();
    }

    @Test
    void normalizesCurrencyCodesToUpperCase() {
        when(exchangeRateService.convert(new BigDecimal("5"), "eur", "EUR"))
                .thenReturn(new BigDecimal("5.00"));

        WalletEstimate estimate = service.estimate(List.of(wallet("eur", "5")), "eur");

        assertThat(estimate.inActiveByCurrency()).containsKey("EUR");
        assertThat(estimate.total()).isEqualByComparingTo("5.00");
    }
}
