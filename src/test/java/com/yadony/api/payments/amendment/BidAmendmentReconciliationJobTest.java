package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Filet planifié : un élément en échec n'arrête pas le lot ; coupé, il ne tourne pas. */
class BidAmendmentReconciliationJobTest {

    private final BidAmendmentRepository amendments = mock(BidAmendmentRepository.class);
    private final BidAmendmentService service = mock(BidAmendmentService.class);
    private final BidAmendmentCashRefunds cashRefunds = mock(BidAmendmentCashRefunds.class);
    private final AdminAlertEscalator alerts = mock(AdminAlertEscalator.class);
    private final BidAmendmentProperties properties = new BidAmendmentProperties();
    private final BidAmendmentReconciliationJob job = new BidAmendmentReconciliationJob(amendments, service,
            cashRefunds, new BidAmendmentWindow(properties), properties, alerts);

    @Test
    void filetCoupe_neTournePas() {
        properties.setReconcileEnabled(false);

        job.scheduledRun();

        verifyNoInteractions(amendments, service, cashRefunds, alerts);
    }

    @Test
    void unElementEnEchec_nArretePasLeLot() {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID amendmentId = UUID.randomUUID();
        when(amendments.findIdsLapsed(any(), any(), any(), any())).thenReturn(List.of(failing, next));
        when(service.expire(failing)).thenThrow(new IllegalStateException("verrou"));
        when(service.expire(next)).thenReturn(true);
        when(amendments.findIdsReminderDue(any(), any(), any())).thenReturn(List.of(failing, next));
        when(service.remindTraveler(failing)).thenThrow(new IllegalStateException("verrou"));
        when(service.remindTraveler(next)).thenReturn(true);
        when(amendments.findBidIdsEndedWith(any(), any(), any(), any())).thenReturn(List.of(failing, next));
        when(service.onBidEnded(failing, "BID_ENDED")).thenThrow(new IllegalStateException("verrou"));
        when(service.onBidEnded(next, "BID_ENDED")).thenReturn(true);
        when(amendments.findCashIdsToSettle(any(), any(), any())).thenReturn(List.of(failing, next));
        when(cashRefunds.settle(failing)).thenThrow(new IllegalStateException("stripe"));
        when(cashRefunds.settle(next)).thenReturn(true);
        when(amendments.findStaleCommissionsWithoutRefund(any(), anyInt())).thenReturn(List.<Object[]>of(
                new Object[]{"pas-un-uuid", bidId.toString(), "pi_bad"},
                new Object[]{amendmentId.toString(), bidId.toString(), "pi_ok"}));
        when(cashRefunds.refundStale(amendmentId, bidId, "pi_ok")).thenReturn(true);
        when(amendments.findIdsUnsettledAfterBidEnd(any(), any(), any(), any(), any())).thenReturn(List.of(failing));
        when(amendments.findById(failing)).thenThrow(new IllegalStateException("base"));

        BidAmendmentReconciliationJob.Outcome outcome = job.run();

        assertThat(outcome).isEqualTo(new BidAmendmentReconciliationJob.Outcome(1, 1, 1, 1, 1, 0));
        verify(cashRefunds, never()).refundStale(any(), any(), org.mockito.ArgumentMatchers.eq("pi_bad"));
    }
}
