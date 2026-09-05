package com.yadony.api.payments.pawapay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.dto.PawapayWalletBalance;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PawapayBalanceMonitorTest {

    @Mock PawapayClient client;
    @Mock AdminAlertService alerts;
    @Mock AdminAlertRepository alertRepository;

    private PawapayBalanceMonitor monitor(BigDecimal minXof, BigDecimal minXaf) {
        return new PawapayBalanceMonitor(client, alerts, alertRepository, new PawapayProperties(true, "https://x", "t",
                false, 30, "https://r", "yadony://bids/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(minXof, minXaf)));
    }

    @Test
    void belowThreshold_raisesOnce() {
        when(client.walletBalances()).thenReturn(List.of(
                new PawapayWalletBalance("SEN", "XOF", new BigDecimal("50000")),
                new PawapayWalletBalance("CMR", "XAF", new BigDecimal("900000"))));
        when(alertRepository.findByTypeAndResolved("PAWAPAY_BALANCE_LOW", false)).thenReturn(List.of());

        monitor(new BigDecimal("100000"), new BigDecimal("100000")).check();

        verify(alerts).raise(eq("PAWAPAY_BALANCE_LOW"), any(), any());
        verify(alertRepository).save(any(AdminAlertEntity.class));
    }

    @Test
    void existingUnresolvedAlert_isNotDuplicated() {
        when(client.walletBalances()).thenReturn(List.of(new PawapayWalletBalance("SEN", "XOF", new BigDecimal("10"))));
        AdminAlertEntity existing = new AdminAlertEntity();
        existing.setType("PAWAPAY_BALANCE_LOW");
        existing.setPayload("{\"currency\":\"XOF\"}");
        when(alertRepository.findByTypeAndResolved("PAWAPAY_BALANCE_LOW", false)).thenReturn(List.of(existing));

        monitor(new BigDecimal("100000"), BigDecimal.ZERO).check();

        verify(alerts, never()).raise(any(), any(), any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void zeroThreshold_disablesTheCheck() {
        monitor(BigDecimal.ZERO, BigDecimal.ZERO).check();
        verify(client, never()).walletBalances();
    }
}
