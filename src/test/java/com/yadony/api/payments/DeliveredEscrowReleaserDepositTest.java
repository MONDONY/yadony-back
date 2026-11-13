package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rattrapage d'un dépôt non versé (BB4) : un colis payé au dépôt est « versable » dès son dépôt (DEPART enregistré,
 * colis toujours dans le parcours), un colis payé à la livraison seulement une fois livré.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveredEscrowReleaser : colis payé au dépôt")
class DeliveredEscrowReleaserDepositTest {

    @Mock PaymentRepository paymentRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock DeliveryEventListener deliveryRelease;
    @Mock AdminAlertEscalator alertEscalator;

    private DeliveredEscrowReleaser releaser;
    private final UUID paymentId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private PaymentEntity payment;
    private BidEntity bid;

    @BeforeEach
    void setUp() {
        releaser = new DeliveredEscrowReleaser(paymentRepository, bidRepository, announcementRepository, deliveryRelease,
                alertEscalator);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setStatus(BidStatus.HANDED_OVER);
        freeze(PayoutTrigger.DEPOSIT);
        lenient().when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));

        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setBidId(bid.getId());
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("50.00"));
        payment.setCommissionAmount(new BigDecimal("6.00"));
        lenient().when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        AnnouncementEntity announcement = mock(AnnouncementEntity.class);
        lenient().when(announcement.getTravelerId()).thenReturn(travelerId);
        lenient().when(announcementRepository.findById(bid.getAnnouncementId())).thenReturn(Optional.of(announcement));
    }

    private void freeze(PayoutTrigger trigger) {
        ReflectionTestUtils.setField(bid, "payoutTrigger", trigger);
        ReflectionTestUtils.setField(bid, "payoutTriggerFrozenAt", LocalDateTime.now().minusDays(2));
    }

    @Test
    @DisplayName("séquestre tardif d'un colis déposé : versé par le chemin de la livraison, source late-escrow")
    void lateEscrow_depositedBid_released() {
        when(bidRepository.findDepartScannedAt(bid.getId())).thenReturn(LocalDateTime.now().minusHours(2));
        when(deliveryRelease.releaseAfterLateEscrow(bid.getId(), bid.getSenderId(), travelerId, "late-escrow"))
                .thenReturn(EscrowReleaseOutcome.RELEASED);

        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow").outcome())
                .isEqualTo(EscrowReleaseOutcome.RELEASED);
        verify(alertEscalator).resolveOpen(DeliveredEscrowReleaser.FAILED_ALERT_PREFIX + paymentId);
    }

    @Test
    @DisplayName("colis payé au dépôt, accepté mais pas déposé : rien n'est versé")
    void lateEscrow_acceptedNotDeposited_notReleased() {
        bid.setStatus(BidStatus.ACCEPTED);

        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow").outcome())
                .isEqualTo(EscrowReleaseOutcome.NOT_DELIVERED);
        verify(deliveryRelease, never()).releaseAfterLateEscrow(any(), any(), any(), any());
    }

    @Test
    @DisplayName("colis payé au dépôt en route mais sans DEPART enregistré : rien n'est versé")
    void lateEscrow_depositBidWithoutDepart_notReleased() {
        bid.setStatus(BidStatus.IN_TRANSIT);
        when(bidRepository.findDepartScannedAt(bid.getId())).thenReturn(null);

        assertThat(releaser.releaseIfDelivered(paymentId, "late-escrow").outcome())
                .isEqualTo(EscrowReleaseOutcome.NOT_DELIVERED);
        verify(deliveryRelease, never()).releaseAfterLateEscrow(any(), any(), any(), any());
    }

    @Test
    @DisplayName("colis payé au dépôt annulé après le dépôt : jamais versé par l'automate")
    void lateEscrow_depositedThenCancelled_notReleased() {
        bid.setStatus(BidStatus.CANCELLED);

        assertThat(releaser.deliveredBidOf(payment)).isEmpty();
        verify(deliveryRelease, never()).releaseAfterLateEscrow(any(), any(), any(), any());
    }

    @Test
    @DisplayName("colis payé à la livraison, remis mais pas livré : toujours pas versable (règle inchangée)")
    void deliveryBid_handedOver_notReleased() {
        freeze(PayoutTrigger.DELIVERY);

        assertThat(releaser.deliveredBidOf(payment)).isEmpty();
        verify(bidRepository, never()).findDepartScannedAt(any());
    }

    @Test
    @DisplayName("colis payé à la livraison et livré : versable (règle inchangée)")
    void deliveryBid_completed_released() {
        freeze(PayoutTrigger.DELIVERY);
        bid.setStatus(BidStatus.COMPLETED);

        assertThat(releaser.deliveredBidOf(payment)).contains(
                new DeliveredEscrowReleaser.DeliveredBid(bid.getId(), bid.getSenderId(), travelerId));
    }

    @Test
    @DisplayName("colis payé au dépôt et livré : versable (rattrapage d'un dépôt resté en séquestre)")
    void depositBid_completed_releasable() {
        bid.setStatus(BidStatus.COMPLETED);
        when(bidRepository.findDepartScannedAt(bid.getId())).thenReturn(LocalDateTime.now().minusDays(10));

        assertThat(releaser.deliveredBidOf(payment)).isPresent();
    }
}
