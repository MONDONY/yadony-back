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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveredEscrowReleaserTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private DeliveryEventListener deliveryRelease;

    private DeliveredEscrowReleaser releaser;
    private final UUID paymentId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private PaymentEntity payment;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        releaser = new DeliveredEscrowReleaser(paymentRepository, bidRepository, announcementRepository, deliveryRelease);
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

        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow")).isEqualTo(EscrowReleaseOutcome.RELEASED);
    }

    @Test
    void classicBidPayment_isFoundByBidId() {
        payment.setNegotiationThreadId(null);
        payment.setBidId(bid.getId());
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(deliveryRelease.releaseAfterLateEscrow(any(), any(), any(), any())).thenReturn(EscrowReleaseOutcome.PAYOUT_HELD);

        assertThat(releaser.releaseIfDelivered(paymentId, "admin-resync-stripe"))
                .isEqualTo(EscrowReleaseOutcome.PAYOUT_HELD);
        verify(bidRepository, never()).findByLinkedNegotiationThreadId(any());
    }

    @Test
    void bidNotDelivered_nothingHappens() {
        bid.setStatus(BidStatus.IN_TRANSIT);
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));

        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow")).isEqualTo(EscrowReleaseOutcome.NOT_DELIVERED);
        verifyNoInteractions(deliveryRelease);
    }

    @Test
    void paymentNotInEscrow_orMissing_nothingHappens() {
        payment.setStatus(PaymentStatus.PENDING);
        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow")).isEqualTo(EscrowReleaseOutcome.NOT_DELIVERED);
        assertThat(releaser.releaseIfDelivered(UUID.randomUUID(), "late-escrow"))
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
    void listener_releasesAndSwallowsAStripeFailure() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));
        when(deliveryRelease.releaseAfterLateEscrow(any(), any(), any(), eq("late-escrow")))
                .thenReturn(EscrowReleaseOutcome.RELEASED)
                .thenThrow(new IllegalStateException("Stripe escrow release failed"));

        releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, paymentId));
        // Échec du Transfer : journalisé, jamais remonté au publieur.
        releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, paymentId));

        verify(deliveryRelease, times(2)).releaseAfterLateEscrow(any(), any(), any(), eq("late-escrow"));
    }

    @Test
    void listener_notDelivered_isSilent() {
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.empty());

        releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, paymentId));

        verifyNoInteractions(deliveryRelease);
    }
}
