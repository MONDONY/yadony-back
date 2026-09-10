package com.yadony.api.requests.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NegotiationDepositExpiryRunnerTest {

    @Mock NegotiationThreadRepository threadRepo;
    @Mock NegotiationService service;
    @Mock AdminAlertEscalator alerts;
    @InjectMocks NegotiationDepositExpiryRunner runner;

    @Test
    void run_expiresEachDueThread_inItsOwnCall_andAlertsOnceOnStuckDeposit() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        when(threadRepo.findIdsAwaitingDepositExpiredBefore(any(), any())).thenReturn(List.of(a, b));
        when(service.expireMobileMoneyDeposit(a)).thenReturn(NegotiationService.DepositExpiryOutcome.REVERTED);
        when(service.expireMobileMoneyDeposit(b)).thenReturn(NegotiationService.DepositExpiryOutcome.DEPOSIT_COMPLETED_NOT_APPLIED);

        runner.expireUnpaidDeposits();

        verify(service).expireMobileMoneyDeposit(a);
        verify(service).expireMobileMoneyDeposit(b);
        verify(alerts).raiseOnce(eq("NEGO_DEPOSIT_DONE_" + b), any(), any());
    }

    @Test
    void run_oneFailureDoesNotStopTheOthers() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        when(threadRepo.findIdsAwaitingDepositExpiredBefore(any(), any())).thenReturn(List.of(a, b));
        when(service.expireMobileMoneyDeposit(a)).thenThrow(new RuntimeException("boom"));
        when(service.expireMobileMoneyDeposit(b)).thenReturn(NegotiationService.DepositExpiryOutcome.IGNORED);

        runner.expireUnpaidDeposits();

        verify(service).expireMobileMoneyDeposit(b);
        verify(alerts, never()).raiseOnce(any(), any(), any());
    }
}
