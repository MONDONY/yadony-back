package com.yadony.api.payments;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveredEscrowReleaserTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private DeliveryEventListener deliveryRelease;
    @Mock private com.yadony.api.admin.AdminAlertEscalator alertEscalator;

    private DeliveredEscrowReleaser releaser;
    private final UUID paymentId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private PaymentEntity payment;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        releaser = new DeliveredEscrowReleaser(paymentRepository, bidRepository, announcementRepository, deliveryRelease,
                alertEscalator);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setNegotiationThreadId(threadId);
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("50.00"));
        payment.setCommissionAmount(new BigDecimal("2.38"));
        lenient().when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setStatus(BidStatus.COMPLETED);
        AnnouncementEntity announcement = mock(AnnouncementEntity.class);
        lenient().when(announcement.getTravelerId()).thenReturn(travelerId);
        lenient().when(announcementRepository.findById(bid.getAnnouncementId())).thenReturn(Optional.of(announcement));
    }

    @Test
    void negotiationPayment_deliveredBid_releasesThroughTheDeliveryPath() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));
        when(deliveryRelease.releaseAfterLateEscrow(bid.getId(), bid.getSenderId(), travelerId, "late-escrow"))
                .thenReturn(EscrowReleaseOutcome.RELEASED);

        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow").outcome()).isEqualTo(EscrowReleaseOutcome.RELEASED);
        verify(alertEscalator).resolveOpen("LATE_RELEASE_FAILED_" + paymentId);
    }

    @Test
    void classicBidPayment_isFoundByBidId() {
        payment.setNegotiationThreadId(null);
        payment.setBidId(bid.getId());
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(deliveryRelease.releaseAfterLateEscrow(any(), any(), any(), any())).thenReturn(EscrowReleaseOutcome.PAYOUT_HELD);

        assertThat(releaser.releaseIfDelivered(paymentId, "admin-resync-stripe").outcome())
                .isEqualTo(EscrowReleaseOutcome.PAYOUT_HELD);
        verifyNoInteractions(alertEscalator);
        verify(bidRepository, never()).findByLinkedNegotiationThreadId(any());
    }

    @Test
    void bidNotDelivered_nothingHappens() {
        bid.setStatus(BidStatus.IN_TRANSIT);
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));

        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow").outcome()).isEqualTo(EscrowReleaseOutcome.NOT_DELIVERED);
        verifyNoInteractions(deliveryRelease);
    }

    @Test
    void paymentNotInEscrow_orMissing_nothingHappens() {
        payment.setStatus(PaymentStatus.PENDING);
        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow").outcome()).isEqualTo(EscrowReleaseOutcome.NOT_DELIVERED);
        assertThat(releaser.releaseIfDelivered(UUID.randomUUID(), "late-escrow").outcome())
                .isEqualTo(EscrowReleaseOutcome.NOT_DELIVERED);
        verifyNoInteractions(bidRepository, deliveryRelease);
    }

    @Test
    void noBid_noThread_ambiguousThread_orNoTraveler_neverReleases() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId))
                .thenThrow(new IncorrectResultSizeDataAccessException(1, 2));
        assertThat(releaser.deliveredBidOf(payment)).isEmpty();

        payment.setNegotiationThreadId(null);
        assertThat(releaser.deliveredBidOf(payment)).isEmpty();

        payment.setBidId(bid.getId());
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(bid.getAnnouncementId())).thenReturn(Optional.empty());
        assertThat(releaser.deliveredBidOf(payment)).isEmpty();
        verifyNoInteractions(deliveryRelease);
    }

    @Test
    void listener_skipsWhenTheCallerSettles() {
        releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, paymentId, true));

        verifyNoInteractions(paymentRepository, deliveryRelease);
    }

    @Test
    void transferRefused_raisesTheLateReleaseAlert_once_andReportsTheReason() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));
        when(deliveryRelease.releaseAfterLateEscrow(any(), any(), any(), eq("late-escrow")))
                .thenThrow(new IllegalStateException("Stripe escrow release failed",
                        new RuntimeException("insufficient funds")));

        DeliveredEscrowReleaser.LateRelease r = releaser.releaseIfDelivered(paymentId, "late-escrow");

        assertThat(r.outcome()).isEqualTo(EscrowReleaseOutcome.TRANSFER_FAILED);
        assertThat(r.failure()).isEqualTo("versement refusé par Stripe (insufficient funds)");
        verify(alertEscalator).raiseOnce(eq("LATE_RELEASE_FAILED_" + paymentId), contains("Forcer le versement"),
                argThat(m -> paymentId.toString().equals(m.get("paymentId")) && bid.getId().toString().equals(m.get("bidId"))
                        && "late-escrow".equals(m.get("source"))));
        assertThat(("LATE_RELEASE_FAILED_" + paymentId).length()).isLessThanOrEqualTo(
                com.yadony.api.admin.AdminAlertEscalator.TYPE_MAX_LENGTH);
    }

    @Test
    void captureFailed_raisesTheLateReleaseAlert() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));
        when(deliveryRelease.releaseAfterLateEscrow(any(), any(), any(), any()))
                .thenReturn(EscrowReleaseOutcome.CAPTURE_FAILED);

        DeliveredEscrowReleaser.LateRelease r = releaser.releaseIfDelivered(paymentId, "auto-heal-release");

        assertThat(r.outcome()).isEqualTo(EscrowReleaseOutcome.CAPTURE_FAILED);
        assertThat(r.failure()).contains("capture du séquestre impossible");
        verify(alertEscalator).raiseOnce(eq("LATE_RELEASE_FAILED_" + paymentId), anyString(), anyMap());
    }

    @Test
    void alertFailures_neverEscape() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));
        when(deliveryRelease.releaseAfterLateEscrow(any(), any(), any(), any()))
                .thenReturn(EscrowReleaseOutcome.CAPTURE_FAILED, EscrowReleaseOutcome.RELEASED);
        when(alertEscalator.raiseOnce(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("db"));
        when(alertEscalator.resolveOpen(anyString())).thenThrow(new IllegalStateException("db"));

        assertThat(releaser.releaseIfDelivered(paymentId, "x").outcome()).isEqualTo(EscrowReleaseOutcome.CAPTURE_FAILED);
        assertThat(releaser.releaseIfDelivered(paymentId, "x").outcome()).isEqualTo(EscrowReleaseOutcome.RELEASED);
    }

    @Test
    void reportFailure_withoutBid_omitsTheBidId() {
        releaser.reportFailure(payment, null, "capture impossible", "admin-resync-stripe");

        verify(alertEscalator).raiseOnce(eq("LATE_RELEASE_FAILED_" + paymentId), anyString(),
                argThat(m -> !m.containsKey("bidId")));
    }

    @Test
    void listener_releasesAndSwallowsAReadFailure() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));
        when(deliveryRelease.releaseAfterLateEscrow(any(), any(), any(), eq("late-escrow")))
                .thenReturn(EscrowReleaseOutcome.RELEASED);

        releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, paymentId));
        when(paymentRepository.findById(paymentId)).thenThrow(new IllegalStateException("base indisponible"));
        // Lecture impossible : journalisée, jamais remontée au publieur.
        releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, paymentId));

        verify(deliveryRelease).releaseAfterLateEscrow(any(), any(), any(), eq("late-escrow"));
    }

    @Test
    void listener_notDelivered_isSilent() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.empty());

        releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, paymentId));

        verifyNoInteractions(deliveryRelease);
    }
}
