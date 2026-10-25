package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private final BidAmendmentCardRail cardRail = mock(BidAmendmentCardRail.class);
    private final BidAmendmentCardLedger cardLedger = mock(BidAmendmentCardLedger.class);
    private final BidAmendmentCardRefunds cardRefunds = mock(BidAmendmentCardRefunds.class);
    private final BidAmendmentPayouts payouts = mock(BidAmendmentPayouts.class);
    private final AdminAlertEscalator alerts = mock(AdminAlertEscalator.class);
    private final BidAmendmentProperties properties = new BidAmendmentProperties();
    private final BidAmendmentReconciliationJob job = new BidAmendmentReconciliationJob(amendments, service,
            cashRefunds, cardRail, cardLedger, cardRefunds, payouts, new BidAmendmentWindow(properties), properties,
            alerts);

    @Test
    void filetCoupe_neTournePas() {
        properties.setReconcileEnabled(false);

        job.scheduledRun();

        verifyNoInteractions(amendments, service, cashRefunds, cardRail, cardRefunds, payouts, alerts);
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
        when(amendments.findIdsReminderDue(any(), org.mockito.ArgumentMatchers.anyLong(), anyInt()))
                .thenReturn(List.of(failing, next));
        when(service.remindTraveler(failing)).thenThrow(new IllegalStateException("verrou"));
        when(service.remindTraveler(next)).thenReturn(true);
        when(amendments.findBidIdsEndedWith(any(), any(), any(), any())).thenReturn(List.of(failing, next));
        when(service.onBidEnded(failing, "BID_ENDED")).thenThrow(new IllegalStateException("verrou"));
        when(service.onBidEnded(next, "BID_ENDED")).thenReturn(true);
        when(amendments.findCashIdsToSettle(any(), any(), any())).thenReturn(List.of(failing, next));
        when(cashRefunds.settle(failing)).thenThrow(new IllegalStateException("stripe"));
        when(cashRefunds.settle(next)).thenReturn(true);
        when(amendments.findStaleCommissionsWithoutRefund(any(), anyInt(), any(), anyInt())).thenReturn(List.<Object[]>of(
                new Object[]{"pas-un-uuid", bidId.toString(), "pi_bad"},
                new Object[]{amendmentId.toString(), bidId.toString(), "pi_ok"}));
        when(cashRefunds.refundStale(amendmentId, bidId, "pi_ok")).thenReturn(true);
        UUID released = UUID.randomUUID();
        when(amendments.findCardIdsAwaitingRelease(any(), any(), any(), any()))
                .thenReturn(List.of(failing, next, released));
        when(cardRail.releaseAuthorization(failing)).thenThrow(new IllegalStateException("stripe"));
        when(cardRail.releaseAuthorization(next)).thenReturn(false);
        when(cardRail.releaseAuthorization(released)).thenReturn(true);
        when(amendments.findIdsUnsettledAfterBidEnd(any(), any(), any(), any(), any(), any())).thenReturn(List.of(failing));
        when(amendments.findById(failing)).thenThrow(new IllegalStateException("base"));

        BidAmendmentReconciliationJob.Outcome outcome = job.run();

        assertThat(outcome).isEqualTo(new BidAmendmentReconciliationJob.Outcome(1, 1, 1, 1, 1, 1, 0, 0, 0));
        verify(cashRefunds, never()).refundStale(any(), any(), org.mockito.ArgumentMatchers.eq("pi_bad"));
        verify(cardRail).releaseAuthorization(next);
        // Échec d'une libération : essai noté (la ligne passe en fin de lot), seulement pour elle.
        verify(cardLedger).releaseAttempted(failing);
        verify(cardLedger, never()).releaseAttempted(next);
        verify(cardLedger, never()).releaseAttempted(released);
        // Échec d'une libération : alerte (le filet repassera) ; seule la libération effective est comptée.
        verify(alerts).raiseOnce(org.mockito.ArgumentMatchers.eq("AMEND_PI_UNCANCELED_" + failing),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("marquage de l'essai raté impossible (base indisponible) : l'alerte part et le lot continue")
    void echecDuMarquage_nArretePasLeLot() {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(amendments.findCardIdsAwaitingRelease(any(), any(), any(), any())).thenReturn(List.of(failing, next));
        when(cardRail.releaseAuthorization(failing)).thenThrow(new IllegalStateException("stripe"));
        doThrow(new IllegalStateException("base")).when(cardLedger).releaseAttempted(failing);
        when(cardRail.releaseAuthorization(next)).thenReturn(true);

        AtomicReference<BidAmendmentReconciliationJob.Outcome> outcome = new AtomicReference<>();
        assertThatCode(() -> outcome.set(job.run())).as("marquage en échec : le lot continue")
                .doesNotThrowAnyException();

        verify(alerts).raiseOnce(eq("AMEND_PI_UNCANCELED_" + failing), anyString(), anyMap());
        verify(cardRail).releaseAuthorization(next);
        assertThat(outcome.get().cardReleased()).isEqualTo(1);
    }

    @Test
    @DisplayName("filet carte : remboursements en suspens, versements à reprendre et versements manqués, sans arrêt du lot")
    void filetCarte_rembourseRepriseVersementManque() {
        UUID failing = UUID.randomUUID();
        UUID refund = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        UUID missed = UUID.randomUUID();
        when(amendments.findCardIdsToRefund(eq(BidAmendmentCardLedger.CLOSED_UNACCEPTED), any(), any()))
                .thenReturn(List.of(failing, refund));
        when(cardRefunds.refund(failing)).thenThrow(new IllegalStateException("stripe"));
        when(cardRefunds.refund(refund)).thenReturn(true);
        when(amendments.findCardIdsPayoutPendingBefore(any(), any())).thenReturn(List.of(failing, pending));
        when(payouts.resume(failing)).thenThrow(new IllegalStateException("stripe"));
        when(payouts.resume(pending)).thenReturn(true);
        when(amendments.findCardIdsAwaitingMissedPayout(any(), eq(BidAmendmentPayouts.BLOCKED_ALERT_PREFIX), anyInt()))
                .thenReturn(List.of(failing, missed));
        when(payouts.payout(failing)).thenThrow(new IllegalStateException("stripe"));
        when(payouts.payout(missed)).thenReturn(true);

        BidAmendmentReconciliationJob.Outcome outcome = job.run();

        assertThat(outcome.cardRefunded()).isEqualTo(1);
        assertThat(outcome.paidOut()).isEqualTo(2);
        verify(cardRefunds).refund(refund);
        verify(payouts).resume(pending);
        verify(payouts).payout(missed);
    }

    @Test
    @DisplayName("filet carte : grâces de 10 min (remboursements, reprises) et de 30 min (versement manqué)")
    void filetCarte_graces() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        BidAmendmentWindow fixed = mock(BidAmendmentWindow.class);
        when(fixed.now()).thenReturn(now);
        BidAmendmentReconciliationJob atNow = new BidAmendmentReconciliationJob(amendments, service, cashRefunds,
                cardRail, cardLedger, cardRefunds, payouts, fixed, properties, alerts);

        atNow.run();

        verify(amendments).findCardIdsToRefund(any(), eq(now.minusMinutes(10)), any());
        verify(amendments).findCardIdsPayoutPendingBefore(eq(now.minusMinutes(10)), any());
        verify(amendments).findCardIdsAwaitingMissedPayout(eq(now.minusMinutes(30)), anyString(), anyInt());
    }
}
