package com.yadony.api.payments.currency;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateUpdateServiceTest {

    @Mock ExchangeRateRepository exchangeRateRepository;
    @Mock AuditService auditService;
    @Mock CacheManager cacheManager;
    @Mock Cache cache;
    @Mock ApplicationEventPublisher eventPublisher;

    private ExchangeRateUpdateService service() {
        lenient().when(cacheManager.getCache("exchange-rates")).thenReturn(cache);
        return new ExchangeRateUpdateService(
                exchangeRateRepository, auditService, cacheManager, eventPublisher);
    }

    @Test
    @DisplayName("apply — save, éviction, event de repivot et audit, dans cet ordre logique")
    void apply_savesEvictsPublishesAndAudits() {
        ExchangeRateEntity usd = new ExchangeRateEntity("USD", new BigDecimal("1.08"));
        when(exchangeRateRepository.findByCurrency("USD")).thenReturn(Optional.of(usd));
        when(exchangeRateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID adminId = UUID.randomUUID();

        ExchangeRateEntity saved = service().apply(
                "usd", new BigDecimal("1.1642"), adminId, "EXCHANGE_RATE_UPDATED");

        assertThat(saved.getUnitsPerEur()).isEqualByComparingTo("1.1642");
        assertThat(saved.getUpdatedBy()).isEqualTo(adminId);
        verify(cache).evict("USD");
        verify(eventPublisher).publishEvent(
                new ExchangeRateChangedEvent("USD", new BigDecimal("1.1642")));
        verify(auditService).log(
                org.mockito.ArgumentMatchers.eq("EXCHANGE_RATE"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("EXCHANGE_RATE_UPDATED"),
                org.mockito.ArgumentMatchers.eq(adminId),
                org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("XOF/XAF — parité fixe, refusés quel que soit l'appelant")
    void apply_fixedParity_isRejected() {
        assertThatThrownBy(() -> service().apply(
                "xof", new BigDecimal("700"), null, "EXCHANGE_RATE_SYNCED"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getErrorCode())
                        .isEqualTo("exchange-rate-fixed-parity"));
        verify(exchangeRateRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("bornes — nul, négatif ou > 10 000 refusés avant tout save")
    void apply_outOfRange_isRejected() {
        for (BigDecimal bad : new BigDecimal[]{null, new BigDecimal("-1"),
                BigDecimal.ZERO, new BigDecimal("10001")}) {
            assertThatThrownBy(() -> service().apply("USD", bad, null, "EXCHANGE_RATE_SYNCED"))
                    .isInstanceOf(YadonyBusinessException.class);
        }
        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    @DisplayName("devise sans ligne exchange_rates — 404, rien n'est créé")
    void apply_unknownCurrency_isRejected() {
        when(exchangeRateRepository.findByCurrency("USD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().apply(
                "USD", new BigDecimal("1.10"), null, "EXCHANGE_RATE_SYNCED"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getErrorCode())
                        .isEqualTo("exchange-rate-not-found"));
        verify(exchangeRateRepository, never()).save(any());
    }
}
