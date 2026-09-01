package com.yadony.api.payments.currency;

import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateSyncServiceTest {

    @Mock EcbRateClient ecbRateClient;
    @Mock ExchangeRateRepository exchangeRateRepository;
    @Mock ExchangeRateUpdateService updateService;
    @Mock AdminAlertService adminAlertService;

    private ExchangeRateSyncService service(String maxChange) {
        return new ExchangeRateSyncService(ecbRateClient, exchangeRateRepository,
                updateService, adminAlertService, new BigDecimal(maxChange));
    }

    private void stubCurrent(String code, String rate) {
        when(exchangeRateRepository.findByCurrency(code))
                .thenReturn(Optional.of(new ExchangeRateEntity(code, new BigDecimal(rate))));
    }

    @Test
    @DisplayName("variation sous le garde-fou → appliquée via le point d'écriture unique, audit SYNCED")
    void syncAll_appliesRatesWithinGuardrail() {
        when(ecbRateClient.fetchDailyRates()).thenReturn(Map.of(
                "USD", new BigDecimal("1.1642")));
        when(exchangeRateRepository.findByCurrency(anyString())).thenAnswer(inv ->
                "USD".equals(inv.getArgument(0))
                        ? Optional.of(new ExchangeRateEntity("USD", new BigDecimal("1.08")))
                        : Optional.empty());

        int updated = service("0.10").syncAll();

        assertThat(updated).isEqualTo(1);
        // actorId null : c'est le robot, pas une main humaine — l'audit le distingue.
        verify(updateService).apply(eq("USD"), eq(new BigDecimal("1.1642")),
                isNull(), eq("EXCHANGE_RATE_SYNCED"));
        verify(adminAlertService, never()).raise(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("variation au-delà du garde-fou → NON appliquée, alerte admin")
    void syncAll_rejectsSuspiciousJump_andAlerts() {
        when(ecbRateClient.fetchDailyRates()).thenReturn(Map.of(
                "USD", new BigDecimal("2.50")));   // +131 % : flux corrompu ou bug
        stubCurrent("USD", "1.08");

        int updated = service("0.10").syncAll();

        assertThat(updated).isZero();
        verify(updateService, never()).apply(any(), any(), any(), any());
        verify(adminAlertService).raise(eq("EXCHANGE_RATE_SYNC_REJECTED"), anyString(), anyMap());
    }

    @Test
    @DisplayName("taux identique → aucune écriture (idempotence du scheduler)")
    void syncAll_identicalRate_isNoOp() {
        when(ecbRateClient.fetchDailyRates()).thenReturn(Map.of(
                "USD", new BigDecimal("1.08")));
        stubCurrent("USD", "1.08");

        int updated = service("0.10").syncAll();

        assertThat(updated).isZero();
        verify(updateService, never()).apply(any(), any(), any(), any());
    }

    @Test
    @DisplayName("XOF/XAF et EUR jamais synchronisés, même présents côté flux")
    void syncAll_neverTouchesFixedParityNorEur() {
        // Flux hostile portant EUR et XOF : le périmètre reste les flottantes.
        when(ecbRateClient.fetchDailyRates()).thenReturn(Map.of(
                "EUR", new BigDecimal("0.99"),
                "XOF", new BigDecimal("700")));

        int updated = service("0.10").syncAll();

        assertThat(updated).isZero();
        verify(updateService, never()).apply(any(), any(), any(), any());
        verify(exchangeRateRepository, never()).findByCurrency("XOF");
        verify(exchangeRateRepository, never()).findByCurrency("EUR");
    }

    @Test
    @DisplayName("flux vide (BCE injoignable) → aucun effet, les taux de la veille restent")
    void syncAll_emptyFeed_isNoOp() {
        when(ecbRateClient.fetchDailyRates()).thenReturn(Map.of());

        assertThat(service("0.10").syncAll()).isZero();
        verify(updateService, never()).apply(any(), any(), any(), any());
    }

    @Test
    @DisplayName("devise du catalogue absente du flux du jour → inchangée, pas d'invention")
    void syncAll_currencyMissingFromFeed_isSkipped() {
        when(ecbRateClient.fetchDailyRates()).thenReturn(Map.of(
                "USD", new BigDecimal("1.1642")));   // CAD/GBP/CHF absents
        when(exchangeRateRepository.findByCurrency(anyString())).thenAnswer(inv ->
                "USD".equals(inv.getArgument(0))
                        ? Optional.of(new ExchangeRateEntity("USD", new BigDecimal("1.08")))
                        : Optional.empty());

        int updated = service("0.10").syncAll();

        assertThat(updated).isEqualTo(1);
        verify(updateService).apply(eq("USD"), any(), isNull(), any());
    }

    @Test
    @DisplayName("une devise en échec n'emporte pas les autres — alerte et continue")
    void syncAll_oneFailureDoesNotBlockOthers() {
        when(ecbRateClient.fetchDailyRates()).thenReturn(Map.of(
                "USD", new BigDecimal("1.1642"),
                "CAD", new BigDecimal("1.6033")));
        when(exchangeRateRepository.findByCurrency(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            return switch (code) {
                case "USD" -> Optional.of(new ExchangeRateEntity("USD", new BigDecimal("1.08")));
                case "CAD" -> Optional.of(new ExchangeRateEntity("CAD", new BigDecimal("1.47")));
                default -> Optional.empty();
            };
        });
        // Strict stubs : sans stub USD, l'appel léverait PotentialStubbingProblem
        // (même méthode, autres args), que le catch du service avalerait — faux vert.
        when(updateService.apply(eq("USD"), any(), any(), any())).thenReturn(null);
        when(updateService.apply(eq("CAD"), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        int updated = service("0.10").syncAll();

        assertThat(updated).isEqualTo(1);   // USD passé malgré CAD en échec
        verify(adminAlertService).raise(eq("EXCHANGE_RATE_SYNC_FAILED"), anyString(), anyMap());
    }
}
