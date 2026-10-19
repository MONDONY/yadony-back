package com.yadony.api.payments.supplement;

import com.yadony.api.payments.cash.CommissionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BidSupplementCashReconciliationJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final BidSupplementRepository repository = mock(BidSupplementRepository.class);
    private final BidSupplementService service = mock(BidSupplementService.class);
    private final BidSupplementCashRefunds refunds = mock(BidSupplementCashRefunds.class);
    private final BidSupplementProperties properties = new BidSupplementProperties();
    private final BidSupplementCashReconciliationJob job = new BidSupplementCashReconciliationJob(repository, service,
            refunds, new BidSupplementWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC)), properties);

    @Test
    void annuleLes3dsJamaisConfirmeesApres30Minutes_unEchecNArretePasLeLot() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        LocalDateTime before = NOW_UTC.minusMinutes(30);
        when(repository.findIdsAwaitingCommissionBefore(eq(before), any(Pageable.class))).thenReturn(List.of(failing, ok));
        when(service.cancelUnconfirmedCommission(failing, before)).thenThrow(new IllegalStateException("verrou"));
        when(service.cancelUnconfirmedCommission(ok, before)).thenReturn(true);

        assertThat(job.run().cancelled()).isEqualTo(1);
    }

    @Test
    void soldeLesCommissionsEnSuspensDesDemandesCloses() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        UUID nothing = UUID.randomUUID();
        when(repository.findCashIdsWithCommissionToSettle(eq(BidSupplementCashLedger.SETTLEABLE),
                eq(EnumSet.of(CommissionStatus.CHARGED, CommissionStatus.REQUIRES_3DS)), eq(NOW_UTC.minusMinutes(5)),
                any(Pageable.class))).thenReturn(List.of(failing, ok, nothing));
        when(refunds.settle(failing)).thenThrow(new IllegalStateException("stripe"));
        when(refunds.settle(ok)).thenReturn(true);

        assertThat(job.run().settled()).isEqualTo(1);
        verify(refunds).settle(nothing);
        assertThat(BidSupplementCashLedger.SETTLEABLE).contains(BidSupplementStatus.REFUNDED)
                .doesNotContain(BidSupplementStatus.PAID, BidSupplementStatus.PROPOSED);
    }

    @Test
    void filetCoupe_rienNeTourne() {
        properties.setReconcileEnabled(false);
        job.scheduledRun();
        verifyNoInteractions(repository, service, refunds);

        properties.setReconcileEnabled(true);
        job.scheduledRun();
        verify(repository).findIdsAwaitingCommissionBefore(any(), any());
        verify(repository).findCashIdsWithCommissionToSettle(any(), any(), any(), any());
    }
}
