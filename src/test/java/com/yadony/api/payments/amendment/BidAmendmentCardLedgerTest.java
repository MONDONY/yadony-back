package com.yadony.api.payments.amendment;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundedEvent;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Registre du rail carte : transitions sous verrou, rejeux sans effet, cas qui ne doivent jamais arriver. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BidAmendmentCardLedger")
class BidAmendmentCardLedgerTest {

    @Mock BidAmendmentRepository amendments;
    @Mock BidAmendmentService service;
    @Mock BidRepository bids;
    @Mock AnnouncementRepository announcements;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;
    @Mock BidAmendmentWindow window;
    @Mock AdminAlertEscalator alerts;

    private BidAmendmentCardLedger ledger;
    private BidAmendmentEntity a;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        ledger = new BidAmendmentCardLedger(amendments, service, bids, announcements, auditService, events, window,
                alerts);
        bid = new BidEntity();
        BidAmendmentTestSupport.setId(bid, UUID.randomUUID());
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setStatus(BidStatus.ACCEPTED);
        a = new BidAmendmentEntity();
        BidAmendmentTestSupport.setId(a, UUID.randomUUID());
        a.setBidId(bid.getId());
        a.setSenderId(UUID.randomUUID());
        a.setRail(BidSupplementRail.STRIPE);
        a.setStatus(BidAmendmentStatus.AUTHORIZATION_PENDING);
        a.setCurrency("EUR");
        a.setGrossAmount(new BigDecimal("33.60"));
        a.setStripePaymentIntentId("pi_1");
        when(amendments.findBidIdById(a.getId())).thenReturn(Optional.of(bid.getId()));
        when(bids.lockForUpdate(bid.getId())).thenReturn(1);
        when(bids.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcements.findByIdForUpdate(bid.getAnnouncementId())).thenReturn(Optional.of(new AnnouncementEntity()));
        when(amendments.findByIdAndBidIdForUpdate(a.getId(), bid.getId())).thenReturn(Optional.of(a));
        when(amendments.findById(a.getId())).thenReturn(Optional.of(a));
        when(window.now()).thenReturn(LocalDateTime.now(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("autorisation : colis plus accepté → annulée BID_ENDED, jamais présentée")
    void authorize_bidNoLongerAccepted_cancels() {
        bid.setStatus(BidStatus.CANCELLED);

        ledger.authorize(a.getId(), "pi_1", "requires_capture", "webhook");

        verify(service).cancelOpen(a, BidAmendmentClosedReason.BID_ENDED);
        verify(service, never()).markProposed(any());
    }

    @Test
    @DisplayName("autorisation : PaymentIntent d'une autre modification, statut non autorisé, autre rail → rien")
    void authorize_foreignOrUnauthorized_noop() {
        ledger.authorize(a.getId(), "pi_other", "requires_capture", "webhook");
        ledger.authorize(a.getId(), "pi_1", "requires_payment_method", "webhook");
        a.setRail(BidSupplementRail.CASH);
        assertThat(ledger.authorize(a.getId(), "pi_1", "requires_capture", "webhook")).isEmpty();

        verify(service, never()).markProposed(any());
        verify(service, never()).cancelOpen(any(), any());
    }

    @Test
    @DisplayName("autorisation : webhook arrivé avant l'enregistrement de la PaymentIntent → adoptée, présentée")
    void authorize_beforeStorage_adoptsPaymentIntent() {
        a.setStripePaymentIntentId(null);

        ledger.authorize(a.getId(), "pi_1", "requires_capture", "webhook");

        assertThat(a.getStripePaymentIntentId()).isEqualTo("pi_1");
        verify(service).markProposed(a);
    }

    @Test
    @DisplayName("capture sur une demande jamais présentée : alerte, rien n'est accepté")
    void captured_whileAuthorizationPending_alerts() {
        assertThat(ledger.captured(a.getId(), "pi_1", "ch_1", "webhook"))
                .isEqualTo(BidAmendmentCardLedger.CaptureOutcome.ALREADY);

        verify(alerts).raiseOnce(eq("AMEND_UNEXPECTED_CAPT_" + a.getId()), anyString(), anyMap());
        verify(service, never()).markAccepted(any());
    }

    @Test
    @DisplayName("capture tardive : remboursement demandé une seule fois, rejeu sans effet ; autre PaymentIntent ignorée")
    void captured_late_requestsRefundOnce() {
        a.setStatus(BidAmendmentStatus.EXPIRED);

        assertThat(ledger.captured(a.getId(), "pi_1", "ch_1", "webhook"))
                .isEqualTo(BidAmendmentCardLedger.CaptureOutcome.LATE_REFUND);
        assertThat(ledger.captured(a.getId(), "pi_1", "ch_1", "accept"))
                .isEqualTo(BidAmendmentCardLedger.CaptureOutcome.ALREADY);
        assertThat(ledger.captured(a.getId(), "pi_other", "ch_9", "webhook"))
                .isEqualTo(BidAmendmentCardLedger.CaptureOutcome.IGNORED);

        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(BidAmendmentRefundRequestedEvent.class));
        assertThat(a.getStripeChargeId()).isEqualTo("ch_1");
    }

    @Test
    @DisplayName("PaymentIntent annulée chez Stripe : en attente → AUTHORIZATION_NOT_CONFIRMED ; close ou capturée → rien")
    void paymentIntentCanceled_byStatus() {
        when(service.cancelOpen(a, BidAmendmentClosedReason.AUTHORIZATION_NOT_CONFIRMED)).thenReturn(true);
        assertThat(ledger.paymentIntentCanceled(a.getId(), "pi_1")).isTrue();

        a.setStatus(BidAmendmentStatus.DECLINED);
        assertThat(ledger.paymentIntentCanceled(a.getId(), "pi_1")).isFalse();
        a.setStatus(BidAmendmentStatus.PROPOSED);
        a.setStripeChargeId("ch_1");
        assertThat(ledger.paymentIntentCanceled(a.getId(), "pi_1")).isFalse();
    }

    @Test
    @DisplayName("création de la PaymentIntent impossible : la demande en attente est annulée, une autre ne bouge pas")
    void abandonAuthorization_onlyWhilePending() {
        ledger.abandonAuthorization(a.getId());
        verify(service).cancelOpen(a, BidAmendmentClosedReason.AUTHORIZATION_NOT_CONFIRMED);

        a.setStatus(BidAmendmentStatus.CANCELLED);
        ledger.abandonAuthorization(a.getId());
        verify(service, org.mockito.Mockito.times(1)).cancelOpen(any(), any());
    }

    @Test
    @DisplayName("remboursement : rien pour une modification acquise ou jamais capturée ; capture tardive → oui")
    void beginRefund_decisions() {
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        assertThat(ledger.beginRefund(a.getId()).decision()).isEqualTo(BidAmendmentCardLedger.RefundDecision.NOTHING);
        a.setStatus(BidAmendmentStatus.CANCELLED);
        assertThat(ledger.beginRefund(a.getId()).decision()).isEqualTo(BidAmendmentCardLedger.RefundDecision.NOTHING);
        a.setStripeChargeId("ch_1");
        a.setStripeRefundId("re_failed");
        BidAmendmentCardLedger.RefundClaim claim = ledger.beginRefund(a.getId());
        assertThat(claim.decision()).isEqualTo(BidAmendmentCardLedger.RefundDecision.PROCEED);
        assertThat(BidAmendmentCardRefunds.refundKey(claim))
                .isEqualTo("amendment-refund-" + a.getId() + "-after-re_failed");
    }

    @Test
    @DisplayName("remboursement constaté : vivante → LIVE ; déjà remboursée → ALREADY")
    void refunded_liveOrAlready() {
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        assertThat(ledger.refunded(a.getId(), "re_1", "webhook")).isEqualTo(BidAmendmentCardLedger.RefundOutcome.LIVE);
        a.setStatus(BidAmendmentStatus.REFUNDED);
        assertThat(ledger.refunded(a.getId(), "re_1", "webhook"))
                .isEqualTo(BidAmendmentCardLedger.RefundOutcome.ALREADY);
        verify(events, never()).publishEvent(any(BidAmendmentRefundedEvent.class));
    }

    @Test
    @DisplayName("Refund échoué : REFUNDED → REFUND_PENDING ; un vieux Refund ne défait rien")
    void refundFailed_reopensOnlyForTheRecordedRefund() {
        a.setStatus(BidAmendmentStatus.REFUNDED);
        a.setRefundedAt(LocalDateTime.now(ZoneOffset.UTC));
        a.setStripeRefundId("re_1");

        assertThat(ledger.refundFailed(a.getId(), "re_old")).isFalse();
        assertThat(ledger.refundFailed(a.getId(), "re_1")).isTrue();

        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.REFUND_PENDING);
        assertThat(a.getRefundedAt()).isNull();
        a.setStatus(BidAmendmentStatus.ACCEPTED);
        assertThat(ledger.refundFailed(a.getId(), "re_1")).isFalse();
    }

    @Test
    @DisplayName("modification introuvable : chaque transition est sans effet")
    void unknownAmendment_noop() {
        UUID unknown = UUID.randomUUID();
        when(amendments.findBidIdById(unknown)).thenReturn(Optional.empty());

        assertThat(ledger.captured(unknown, "pi_1", "ch_1", "webhook"))
                .isEqualTo(BidAmendmentCardLedger.CaptureOutcome.IGNORED);
        assertThat(ledger.captureFailed(unknown, "x")).isFalse();
        assertThat(ledger.refunded(unknown, "re", "webhook")).isEqualTo(BidAmendmentCardLedger.RefundOutcome.IGNORED);
        assertThat(ledger.storePaymentIntent(unknown, "pi_1")).isEmpty();
        verifyNoInteractions(service, events, alerts);
    }

    @Test
    @DisplayName("capture sans charge : ignorée, rien n'est accepté ni remboursé (l'appelant relit la PaymentIntent)")
    void captured_withoutCharge_ignored() {
        a.setStatus(BidAmendmentStatus.PROPOSED);
        assertThat(ledger.captured(a.getId(), "pi_1", null, "webhook"))
                .isEqualTo(BidAmendmentCardLedger.CaptureOutcome.IGNORED);
        a.setStatus(BidAmendmentStatus.CANCELLED);
        assertThat(ledger.captured(a.getId(), "pi_1", " ", "webhook"))
                .isEqualTo(BidAmendmentCardLedger.CaptureOutcome.IGNORED);

        verify(service, never()).markAccepted(any());
        verify(events, never()).publishEvent(any(BidAmendmentRefundRequestedEvent.class));
        assertThat(a.getStripeChargeId()).isNull();

        // La charge relue arrive ensuite : la capture tardive demande bien le remboursement.
        assertThat(ledger.captured(a.getId(), "pi_1", "ch_1", "webhook"))
                .isEqualTo(BidAmendmentCardLedger.CaptureOutcome.LATE_REFUND);
    }

    @Test
    @DisplayName("autorisation arrivée sur une demande close : à rendre, sauf si déjà rendue ou capturée")
    void authorize_onClosed_signalsRelease() {
        a.setStatus(BidAmendmentStatus.CANCELLED);
        assertThat(ledger.authorize(a.getId(), "pi_1", "requires_capture", "webhook")).get()
                .satisfies(r -> assertThat(r.releaseAuthorization()).isTrue());

        a.setAuthorizationReleasedAt(LocalDateTime.now(ZoneOffset.UTC));
        assertThat(ledger.authorize(a.getId(), "pi_1", "requires_capture", "webhook")).get()
                .satisfies(r -> assertThat(r.releaseAuthorization()).isFalse());
        a.setAuthorizationReleasedAt(null);
        a.setStripeChargeId("ch_1");
        assertThat(ledger.authorize(a.getId(), "pi_1", "requires_capture", "webhook")).get()
                .satisfies(r -> assertThat(r.releaseAuthorization()).isFalse());
        verify(service, never()).markProposed(any());
    }

    @Test
    @DisplayName("autorisation rendue : marqueur posé une fois ; essai de capture incrémenté")
    void authorizationReleased_andCaptureAttempt() {
        a.setStatus(BidAmendmentStatus.DECLINED);

        ledger.authorizationReleased(a.getId(), "canceled");
        LocalDateTime first = a.getAuthorizationReleasedAt();
        ledger.authorizationReleased(a.getId(), "canceled");
        ledger.nextCaptureAttempt(a.getId());

        assertThat(first).isNotNull();
        assertThat(a.getAuthorizationReleasedAt()).isSameAs(first);
        verify(auditService, org.mockito.Mockito.times(1)).log(any(), any(), eq("BID_AMENDMENT_AUTHORIZATION_RELEASED"),
                any(), anyMap());
        assertThat(a.getCaptureAttempt()).isEqualTo(1);
        assertThat(BidAmendmentCardRail.captureKey(a.getId(), 0)).isEqualTo("amendment-capture-" + a.getId());
        assertThat(BidAmendmentCardRail.captureKey(a.getId(), 1)).isEqualTo("amendment-capture-" + a.getId() + "-r1");
    }

    @Test
    @DisplayName("autorisation sans PaymentIntent (webhook illisible) sur une demande sans PI : jamais présentée")
    void authorize_nullPaymentIntent_neverProposes() {
        a.setStripePaymentIntentId(null);

        ledger.authorize(a.getId(), null, "requires_capture", "webhook");

        verify(service, never()).markProposed(any());
        verify(service, never()).cancelOpen(any(), any());
        verify(service, never()).expireUnderBidLock(any());
        assertThat(a.getStatus()).isEqualTo(BidAmendmentStatus.AUTHORIZATION_PENDING);
    }

    @Test
    @DisplayName("essai de rendu en échec : date posée (ordre du filet)")
    void releaseAttempted_setsDate() {
        a.setStatus(BidAmendmentStatus.DECLINED);

        ledger.releaseAttempted(a.getId());

        assertThat(a.getReleaseAttemptedAt()).isNotNull();
    }

    @Test
    @DisplayName("prédicat unique de libération : seuls DECLINED, EXPIRED, CANCELLED (closes sans avoir été acquises)")
    void awaitsRelease_onlyClosedUnaccepted_sameForRailAndLedger() {
        // REFUND_PENDING et REFUNDED sont des modifications acquises puis rendues : leur PaymentIntent a été capturée,
        // il n'y a plus d'autorisation à libérer, seulement un remboursement.
        assertThat(BidAmendmentCardLedger.CLOSED_UNACCEPTED).containsExactlyInAnyOrder(BidAmendmentStatus.DECLINED,
                BidAmendmentStatus.EXPIRED, BidAmendmentStatus.CANCELLED);
        for (BidAmendmentStatus status : BidAmendmentStatus.values()) {
            a.setStatus(status);
            boolean expected = BidAmendmentCardLedger.CLOSED_UNACCEPTED.contains(status);
            assertThat(BidAmendmentCardRail.awaitsRelease(a)).as("rail, " + status).isEqualTo(expected);
            if (status != BidAmendmentStatus.AUTHORIZATION_PENDING) {
                assertThat(ledger.authorize(a.getId(), "pi_1", "requires_capture", "webhook")).get()
                        .satisfies(r -> assertThat(r.releaseAuthorization()).as("ledger, " + status)
                                .isEqualTo(expected));
            }
        }
    }
}
