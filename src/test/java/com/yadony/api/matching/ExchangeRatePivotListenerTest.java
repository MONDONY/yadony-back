package com.yadony.api.matching;

import com.yadony.api.payments.currency.ExchangeRateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRatePivotListenerTest {

    @Mock AnnouncementRepository announcementRepository;
    @InjectMocks ExchangeRatePivotListener listener;

    @Test
    void onExchangeRateChanged_recomputesPivotsForTheCurrency() {
        when(announcementRepository.recomputeEurPivotForCurrency("USD", new BigDecimal("1.1642")))
                .thenReturn(42);

        listener.onExchangeRateChanged(new ExchangeRateChangedEvent("USD", new BigDecimal("1.1642")));

        verify(announcementRepository).recomputeEurPivotForCurrency("USD", new BigDecimal("1.1642"));
    }

    @Test
    void onExchangeRateChanged_neverSwallowsFailure_rateAndPivotsCommitTogether() {
        // Listener synchrone même transaction : un repivot en échec doit annuler le
        // changement de taux, pas être avalé.
        when(announcementRepository.recomputeEurPivotForCurrency("USD", BigDecimal.ONE))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> listener.onExchangeRateChanged(
                new ExchangeRateChangedEvent("USD", BigDecimal.ONE)))
                .isInstanceOf(IllegalStateException.class);
    }
}
