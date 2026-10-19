package com.yadony.api.payments.supplement;

import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Filet mobile money : dépôts non appliqués, remboursements et versements en suspens, versements manqués. */
@ExtendWith(MockitoExtension.class)
class BidSupplementMobileMoneyReconciliationJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock BidSupplementRepository supplements;
    @Mock BidSupplementMobileMoneyLedger ledger;
    @Mock PawapayOperationService operations;
    @Mock BidSupplementMobileMoneyPayouts payouts;
    @Mock BidSupplementMobileMoneyRefunds refunds;

    private final BidSupplementProperties properties = new BidSupplementProperties();
    private BidSupplementMobileMoneyReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new BidSupplementMobileMoneyReconciliationJob(supplements, ledger, operations, payouts, refunds,
                new BidSupplementWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC)), properties);
        lenient().when(supplements.findIdsWithUnappliedMobileMoneyDeposit(any(), any(), any())).thenReturn(List.of());
        lenient().when(supplements.findIdsByRailAndStatusInUpdatedBefore(any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(supplements.findIdsPaidLateUnrefunded(any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(supplements.findIdsAwaitingMissedMobileMoneyPayout(any(), any())).thenReturn(List.of());
    }

    @Test
    void depotAbouti_jamaisApplique_paye() {
        UUID pending = UUID.randomUUID();
        UUID already = UUID.randomUUID();
        UUID vanished = UUID.randomUUID();
        when(supplements.findIdsWithUnappliedMobileMoneyDeposit(
                eq(BidSupplementMobileMoneyReconciliationJob.UNAPPLIED_DEPOSIT_STATUSES), eq(NOW_UTC.minusMinutes(10)),
                any())).thenReturn(List.of(pending, already, vanished));
        PawapayOperationEntity deposit = completedDeposit(pending);
        when(operations.findLiveForSupplement(pending, PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        PawapayOperationEntity other = completedDeposit(already);
        when(operations.findLiveForSupplement(already, PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(other));
        when(operations.findLiveForSupplement(vanished, PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(ledger.markPaid(pending, deposit.getId(), "reconcile")).thenReturn(BidSupplementCardLedger.PaidOutcome.PAID);
        when(ledger.markPaid(already, other.getId(), "reconcile")).thenReturn(BidSupplementCardLedger.PaidOutcome.ALREADY);

        assertThat(job.run().deposits()).isEqualTo(1);
    }

    @Test
    void remboursementsEtVersements_enSuspens_repris() {
        UUID refundPending = UUID.randomUUID();
        UUID latePaid = UUID.randomUUID();
        UUID payoutPending = UUID.randomUUID();
        UUID missed = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        when(supplements.findIdsByRailAndStatusInUpdatedBefore(eq(BidSupplementRail.PAWAPAY),
                eq(EnumSet.of(BidSupplementStatus.REFUND_PENDING)), any(), any())).thenReturn(List.of(refundPending, failing));
        when(supplements.findIdsPaidLateUnrefunded(eq(BidSupplementRail.PAWAPAY), eq(BidSupplementCardLedger.CLOSED_UNPAID),
                any(), any())).thenReturn(List.of(latePaid));
        when(supplements.findIdsByRailAndStatusInUpdatedBefore(eq(BidSupplementRail.PAWAPAY),
                eq(EnumSet.of(BidSupplementStatus.PAYOUT_PENDING)), any(), any())).thenReturn(List.of(payoutPending));
        when(supplements.findIdsAwaitingMissedMobileMoneyPayout(eq(NOW_UTC.minusMinutes(30)), any()))
                .thenReturn(List.of(missed));
        when(refunds.refund(refundPending)).thenReturn(true);
        when(refunds.refund(failing)).thenThrow(new IllegalStateException("boom"));
        when(refunds.refund(latePaid)).thenReturn(true);
        when(payouts.resume(payoutPending)).thenReturn(true);
        when(payouts.payout(missed)).thenReturn(false);

        BidSupplementMobileMoneyReconciliationJob.Outcome outcome = job.run();

        assertThat(outcome.refunds()).isEqualTo(2);
        assertThat(outcome.payouts()).isEqualTo(1);
        verify(payouts).payout(missed);
    }

    @Test
    void planifie_seulementSiLeFiletEstActive() {
        properties.setReconcileEnabled(false);
        job.scheduledRun();
        verify(supplements, never()).findIdsAwaitingMissedMobileMoneyPayout(any(), any());
        properties.setReconcileEnabled(true);
        job.scheduledRun();
        verify(supplements).findIdsAwaitingMissedMobileMoneyPayout(any(), any());
    }

    private static PawapayOperationEntity completedDeposit(UUID supplementId) {
        PawapayOperationEntity op = PawapayOperationEntity.forSupplement(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                UUID.randomUUID(), supplementId, null, new BigDecimal("13700"), "XOF", "ORANGE_SEN", "SN",
                "221770000001");
        op.setStatus(PawapayOperationStatus.COMPLETED);
        return op;
    }
}
