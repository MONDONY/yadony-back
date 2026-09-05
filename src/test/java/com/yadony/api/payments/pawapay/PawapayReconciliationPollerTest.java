package com.yadony.api.payments.pawapay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class PawapayReconciliationPollerTest {

    @Mock PawapayOperationRepository repository;
    @Mock PawapayClient client;
    @Mock PawapayOperationService operations;

    private PawapayReconciliationPoller poller() {
        return new PawapayReconciliationPoller(repository, client, operations, props(true));
    }

    private static PawapayProperties props(boolean enabled) {
        return new PawapayProperties(enabled, "https://x", "t", false, 30, "https://r", "yadony://bids/%s/mobile-money/awaiting",
                new PawapayProperties.BalanceMin(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private PawapayOperationEntity op(PawapayOperationStatus status, LocalDateTime createdAt) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                UUID.randomUUID(), null, new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        ReflectionTestUtils.setField(o, "createdAt", createdAt);
        ReflectionTestUtils.setField(o, "updatedAt", createdAt);
        return o;
    }

    @Test
    void openOperation_isReconciledFromPawapayStatus() {
        PawapayOperationEntity o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        when(repository.findByStatusInAndUpdatedAtBefore(eq(PawapayOperationStatus.OPEN), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.of(
                new PawapayOperationSnapshot(PawapayOperationStatus.COMPLETED, null, null, "ptx", null, "{}")));

        poller().reconcile();

        verify(operations).apply(eq(o.getId()), eq(PawapayOperationStatus.COMPLETED), isNull(), isNull(), eq("ptx"),
                isNull(), eq("{}"), eq(PawapayOperationService.Source.POLL));
    }

    @Test
    void createdForLong_andUnknownAtPawapay_becomesSubmitRejected() {
        PawapayOperationEntity o = op(PawapayOperationStatus.CREATED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());

        poller().reconcile();

        verify(operations).apply(eq(o.getId()), eq(PawapayOperationStatus.SUBMIT_REJECTED), eq("SUBMIT_TIMEOUT"), any(),
                isNull(), isNull(), isNull(), eq(PawapayOperationService.Source.SYSTEM));
    }

    @Test
    void createdRecently_andUnknown_isLeftAlone() {
        PawapayOperationEntity o = op(PawapayOperationStatus.CREATED, LocalDateTime.now(ZoneOffset.UTC).minusSeconds(90));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());

        poller().reconcile();

        verify(operations, never()).apply(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptedButUnknown_isNotRejected_onlyLogged() {
        PawapayOperationEntity o = op(PawapayOperationStatus.ACCEPTED, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(o));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, o.getId())).thenReturn(Optional.empty());

        poller().reconcile();

        verify(operations, never()).apply(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void networkError_onOne_doesNotStopTheOthers() {
        PawapayOperationEntity a = op(PawapayOperationStatus.PROCESSING, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        PawapayOperationEntity b = op(PawapayOperationStatus.PROCESSING, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        when(repository.findByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(a, b));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, a.getId())).thenThrow(new ResourceAccessException("boom"));
        when(client.getStatus(PawapayOperationKind.DEPOSIT, b.getId())).thenReturn(Optional.of(
                new PawapayOperationSnapshot(PawapayOperationStatus.FAILED, "PAYMENT_NOT_APPROVED", "no", null, null, "{}")));

        poller().reconcile();

        verify(operations).apply(eq(b.getId()), eq(PawapayOperationStatus.FAILED), eq("PAYMENT_NOT_APPROVED"), eq("no"),
                isNull(), isNull(), eq("{}"), eq(PawapayOperationService.Source.POLL));
    }

    @Test
    void disabled_doesNothing() {
        new PawapayReconciliationPoller(repository, client, operations, props(false)).reconcile();
        verify(repository, never()).findByStatusInAndUpdatedAtBefore(any(), any());
    }
}
