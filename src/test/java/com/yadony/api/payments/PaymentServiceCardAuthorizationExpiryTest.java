package com.yadony.api.payments;

import com.stripe.model.PaymentIntent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidCardAuthorizationExpiredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Une autorisation carte non capturée meurt chez Stripe au bout d'environ 7 jours. Sans ces
 * gardes, un voyageur pouvait accepter après coup un colis dont la capture échouerait, puis le
 * transporter sans argent derrière lui.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Autorisation carte expirée avant l'acceptation du voyageur")
class PaymentServiceCardAuthorizationExpiryTest {

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();

    @Mock PaymentRepository paymentRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;

    PaymentService service;

    @BeforeEach
    void setUp() {
        service = PaymentServiceTestFactory.bare(
                paymentRepository, bidRepository, announcementRepository, eventPublisher, auditService);
    }

    private PaymentIntent canceledPi(String reason) {
        PaymentIntent pi = org.mockito.Mockito.mock(PaymentIntent.class);
        when(pi.getId()).thenReturn("pi_exp");
        org.mockito.Mockito.lenient().when(pi.getCancellationReason()).thenReturn(reason);
        return pi;
    }

    private PaymentEntity heldPayment() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(BID_ID);
        p.setStatus(PaymentStatus.ESCROW);
        return p;
    }

    private BidEntity bid(BidStatus status) {
        BidEntity b = new BidEntity();
        PaymentServiceTestFactory.setId(b, BID_ID);
        b.setStatus(status);
        b.setSenderId(SENDER_ID);
        b.setAnnouncementId(ANNOUNCEMENT_ID);
        return b;
    }

    // ── webhook payment_intent.canceled ─────────────────────────────────────

    @Test
    @DisplayName("annulation automatique Stripe d'une demande en attente : demande éteinte, expéditeur prévenu")
    void automaticCancellation_expiresPendingBid() {
        PaymentEntity payment = heldPayment();
        BidEntity bid = bid(BidStatus.PAYMENT_ESCROWED);
        AnnouncementEntity announcement = org.mockito.Mockito.mock(AnnouncementEntity.class);
        when(announcement.getTravelerId()).thenReturn(TRAVELER_ID);
        when(paymentRepository.findByStripePaymentIntentId("pi_exp")).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(announcement));

        service.applyPaymentIntentCanceled(canceledPi("automatic"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(bid.getStatus()).isEqualTo(BidStatus.EXPIRED);
        assertThat(bid.getRejectionReason()).isEqualTo(BidCardAuthorizationExpiredEvent.REASON);
        verify(bidRepository).save(bid);
        verify(auditService).log(eq("BID"), eq(BID_ID), eq("BID_EXPIRED_CARD_AUTHORIZATION"), any(), any());
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new BidCardAuthorizationExpiredEvent(
                BID_ID, ANNOUNCEMENT_ID, SENDER_ID, TRAVELER_ID));
    }

    @Test
    @DisplayName("trajet introuvable : la demande est éteinte quand même, sans voyageur")
    void automaticCancellation_missingAnnouncement_stillExpires() {
        PaymentEntity payment = heldPayment();
        BidEntity bid = bid(BidStatus.PAYMENT_ESCROWED);
        when(paymentRepository.findByStripePaymentIntentId("pi_exp")).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.empty());

        service.applyPaymentIntentCanceled(canceledPi("automatic"));

        assertThat(bid.getStatus()).isEqualTo(BidStatus.EXPIRED);
        verify(eventPublisher).publishEvent(new BidCardAuthorizationExpiredEvent(
                BID_ID, ANNOUNCEMENT_ID, SENDER_ID, null));
    }

    @Test
    @DisplayName("demande déjà acceptée : jamais éteinte")
    void automaticCancellation_acceptedBid_untouched() {
        PaymentEntity payment = heldPayment();
        BidEntity bid = bid(BidStatus.ACCEPTED);
        when(paymentRepository.findByStripePaymentIntentId("pi_exp")).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.of(bid));

        service.applyPaymentIntentCanceled(canceledPi("automatic"));

        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        verify(bidRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("demande introuvable : rien d'éteint")
    void automaticCancellation_missingBid_noop() {
        when(paymentRepository.findByStripePaymentIntentId("pi_exp")).thenReturn(Optional.of(heldPayment()));
        when(bidRepository.findByIdForUpdate(BID_ID)).thenReturn(Optional.empty());

        service.applyPaymentIntentCanceled(canceledPi("automatic"));

        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("annulation voulue par nous (autre motif ou aucun) : la demande n'est pas éteinte ici")
    void nonAutomaticCancellation_doesNotExpireBid() {
        for (String reason : new String[] {"abandoned", "requested_by_customer", null}) {
            PaymentEntity payment = heldPayment();
            when(paymentRepository.findByStripePaymentIntentId("pi_exp")).thenReturn(Optional.of(payment));

            service.applyPaymentIntentCanceled(canceledPi(reason));

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        }
        verify(bidRepository, never()).findByIdForUpdate(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("paiement jamais autorisé (PENDING) ou déjà capturé : la demande n'est pas éteinte")
    void pendingOrCapturedPayment_doesNotExpireBid() {
        PaymentEntity pending = heldPayment();
        pending.setStatus(PaymentStatus.PENDING);
        PaymentEntity captured = heldPayment();
        ReflectionTestUtils.setField(captured, "capturedAt", Instant.now());
        when(paymentRepository.findByStripePaymentIntentId("pi_exp"))
                .thenReturn(Optional.of(pending), Optional.of(captured));

        service.applyPaymentIntentCanceled(canceledPi("automatic"));
        service.applyPaymentIntentCanceled(canceledPi("automatic"));

        verify(bidRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("paiement d'un fil de négociation (sans demande) : rien à éteindre")
    void paymentWithoutBid_doesNotExpireAnything() {
        PaymentEntity payment = heldPayment();
        payment.setBidId(null);
        when(paymentRepository.findByStripePaymentIntentId("pi_exp")).thenReturn(Optional.of(payment));

        service.applyPaymentIntentCanceled(canceledPi("automatic"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(bidRepository, never()).findByIdForUpdate(any());
    }

    // ── garde de l'acceptation ──────────────────────────────────────────────

    @Test
    @DisplayName("paiement annulé : autorisation perdue")
    void cancelledPayment_isLost() {
        PaymentEntity p = heldPayment();
        p.setStatus(PaymentStatus.CANCELLED);
        when(paymentRepository.findByBidId(BID_ID)).thenReturn(Optional.of(p));

        assertThat(service.isCardAuthorizationLost(BID_ID)).isTrue();
    }

    @Test
    @DisplayName("autorisation de plus de 7 jours non capturée : perdue, même si le webhook tarde")
    void oldUncapturedEscrow_isLost() {
        PaymentEntity p = heldPayment();
        ReflectionTestUtils.setField(p, "createdAt", LocalDateTime.now(ZoneOffset.UTC).minusDays(8));
        when(paymentRepository.findByBidId(BID_ID)).thenReturn(Optional.of(p));

        assertThat(service.isCardAuthorizationLost(BID_ID)).isTrue();
    }

    @Test
    @DisplayName("autorisation de 6 jours : encore valable")
    void recentEscrow_isNotLost() {
        PaymentEntity p = heldPayment();
        ReflectionTestUtils.setField(p, "createdAt", LocalDateTime.now(ZoneOffset.UTC).minusDays(6));
        when(paymentRepository.findByBidId(BID_ID)).thenReturn(Optional.of(p));

        assertThat(service.isCardAuthorizationLost(BID_ID)).isFalse();
    }

    @Test
    @DisplayName("paiement déjà capturé, même ancien : pas perdu")
    void oldCapturedEscrow_isNotLost() {
        PaymentEntity p = heldPayment();
        ReflectionTestUtils.setField(p, "createdAt", LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        ReflectionTestUtils.setField(p, "capturedAt", Instant.now());
        when(paymentRepository.findByBidId(BID_ID)).thenReturn(Optional.of(p));

        assertThat(service.isCardAuthorizationLost(BID_ID)).isFalse();
    }

    @Test
    @DisplayName("aucun paiement, ou date de création inconnue : pas perdu")
    void noPaymentOrUnknownDate_isNotLost() {
        when(paymentRepository.findByBidId(BID_ID)).thenReturn(Optional.empty());
        assertThat(service.isCardAuthorizationLost(BID_ID)).isFalse();

        when(paymentRepository.findByBidId(BID_ID)).thenReturn(Optional.of(heldPayment()));
        assertThat(service.isCardAuthorizationLost(BID_ID)).isFalse();
    }

    @Test
    @DisplayName("paiement libéré ou remboursé : pas concerné par la garde")
    void otherStatuses_areNotLost() {
        PaymentEntity p = heldPayment();
        p.setStatus(PaymentStatus.RELEASED);
        when(paymentRepository.findByBidId(BID_ID)).thenReturn(Optional.of(p));

        assertThat(service.isCardAuthorizationLost(BID_ID)).isFalse();
    }
}
