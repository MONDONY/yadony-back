package com.yadony.api.messaging;

import com.google.cloud.firestore.FieldValue;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidAwaitingPaymentAbandonedEvent;
import com.yadony.api.matching.events.BidCancelledByParticipantEvent;
import com.yadony.api.matching.events.BidCardAuthorizationExpiredEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidNegotiationExpiredEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Reverrouillage : une conversation promue redevient une conversation de questions (plafond
 * réappliqué) quand la demande se termine sans JAMAIS avoir été acceptée. Une demande acceptée
 * puis annulée garde sa conversation de réservation.
 */
@ExtendWith(MockitoExtension.class)
class InquiryRelockTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;

    ConversationService service;
    InquiryPromotionListener listener;

    final UUID askerId = UUID.randomUUID();
    final UUID ownerId = UUID.randomUUID();
    final UUID announcementId = UUID.randomUUID();
    final UUID bidId = UUID.randomUUID();
    ConversationEntity conv;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, firestoreService, userRepository, auditService,
                bidRepository, announcementRepository, mock(StorageService.class), mock(BlockVisibility.class),
                mock(com.yadony.api.common.CallAvailability.class),
                mock(ConversationMediaPolicy.class), mock(MessagingImageRetentionService.class));
        listener = new InquiryPromotionListener(service, bidRepository);
        conv = ConversationEntity.forInquiry(askerId, announcementId, ownerId, "conv_inq_abc");
        ReflectionTestUtils.setField(conv, "id", UUID.randomUUID());
        conv.promoteToBid(bidId, LocalDateTime.now());
        lenient().when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(conv));
        lenient().when(bidRepository.existsByAnnouncementIdAndSenderIdAndStatusNotIn(
                eq(announcementId), eq(askerId), anyCollection())).thenReturn(false);
    }

    private void assertRelocked() {
        assertThat(conv.getKind()).isEqualTo(ConversationKind.INQUIRY);
        assertThat(conv.getBidId()).isNull();
        assertThat(conv.isQuotaLifted()).isFalse();
        verify(conversationRepository).save(conv);
        verify(firestoreService).updateConversation("conv_inq_abc",
                Map.of("kind", "INQUIRY", "quotaLifted", false, "bidId", FieldValue.delete()));
        verify(auditService).log(eq("conversation"), eq(conv.getId()), eq("INQUIRY_RELOCKED"), isNull(), anyMap());
        // Le compteur de messages (inquiryQuota) n'est jamais remis à zéro.
        verifyNoMoreInteractions(firestoreService);
    }

    private void assertStillBooking() {
        assertThat(conv.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);
        assertThat(conv.getBidId()).isEqualTo(bidId);
        assertThat(conv.isQuotaLifted()).isTrue();
        verify(conversationRepository, never()).save(any());
        verifyNoInteractions(firestoreService, auditService);
    }

    // ---- BidRejectedEvent (refus du voyageur : statut d'avant porté par l'événement)

    @Test
    void rejectedWhilePending_relocks() {
        listener.onBidRejected(new BidRejectedEvent(bidId, askerId, "NO_CAPACITY", announcementId, false,
                BidStatus.PENDING));
        assertRelocked();
    }

    @Test
    void rejectedWhilePaymentEscrowed_relocks() {
        listener.onBidRejected(new BidRejectedEvent(bidId, askerId, "OTHER", announcementId, true,
                BidStatus.PAYMENT_ESCROWED));
        assertRelocked();
    }

    @Test
    void rejectedAfterAcceptance_doesNotRelock() {
        listener.onBidRejected(new BidRejectedEvent(bidId, askerId, "SENDER_ACCOUNT_DELETED", announcementId, false,
                BidStatus.ACCEPTED));
        assertStillBooking();
    }

    @Test
    void rejectedWithUnknownPreviousStatus_isNotGuessed() {
        listener.onBidRejected(new BidRejectedEvent(bidId, askerId, "ANNOUNCEMENT_DELETED", announcementId, true));
        assertStillBooking();
    }

    // ---- BidCancelledByParticipantEvent

    @Test
    void cancelledBeforeAcceptance_relocks() {
        for (BidStatus before : new BidStatus[] {BidStatus.AWAITING_PAYMENT, BidStatus.PENDING,
                BidStatus.PAYMENT_ESCROWED, BidStatus.NEGOTIATING}) {
            reset(conversationRepository, firestoreService, auditService);
            if (conv.isInquiry()) {
                conv.promoteToBid(bidId, LocalDateTime.now());
            }
            when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(conv));
            listener.onBidCancelledByParticipant(new BidCancelledByParticipantEvent(
                    bidId, announcementId, askerId, false, before, PaymentMethod.STRIPE));
            assertThat(conv.getKind()).as("avant " + before).isEqualTo(ConversationKind.INQUIRY);
        }
    }

    @Test
    void cancelledByTheSenderBeforeAcceptance_relocksWithAllFields() {
        listener.onBidCancelledByParticipant(new BidCancelledByParticipantEvent(
                bidId, announcementId, askerId, false, BidStatus.PENDING, PaymentMethod.CASH));
        assertRelocked();
    }

    @Test
    void cancelledAwaitingPaymentOnCard_relocks() {
        listener.onBidCancelledByParticipant(new BidCancelledByParticipantEvent(
                bidId, announcementId, askerId, false, BidStatus.AWAITING_PAYMENT, PaymentMethod.STRIPE));
        assertRelocked();
    }

    @Test
    void cancelledAwaitingPaymentOnMobileMoney_doesNotRelock_theTravelerAlreadyAccepted() {
        listener.onBidCancelledByParticipant(new BidCancelledByParticipantEvent(
                bidId, announcementId, askerId, false, BidStatus.AWAITING_PAYMENT, PaymentMethod.MOBILE_MONEY));
        assertStillBooking();
    }

    @Test
    void cancelledAwaitingPaymentWithUnknownPaymentMethod_isNotGuessed() {
        listener.onBidCancelledByParticipant(new BidCancelledByParticipantEvent(
                bidId, announcementId, askerId, false, BidStatus.AWAITING_PAYMENT, null));
        assertStillBooking();
    }

    @Test
    void rejectedWhileAwaitingPayment_doesNotRelock_theEventDoesNotCarryThePaymentMethod() {
        listener.onBidRejected(new BidRejectedEvent(bidId, askerId, "CANCELLED_BY_SENDER", announcementId, false,
                BidStatus.AWAITING_PAYMENT));
        assertStillBooking();
    }

    @Test
    void cancelledAfterAcceptance_doesNotRelock() {
        for (BidStatus before : new BidStatus[] {BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT}) {
            listener.onBidCancelledByParticipant(new BidCancelledByParticipantEvent(
                    bidId, announcementId, ownerId, true, before, PaymentMethod.STRIPE));
        }
        assertStillBooking();
    }

    // ---- Expirations : toutes antérieures à l'acceptation par construction

    @Test
    void negotiationExpired_relocks() {
        listener.onNegotiationExpired(new BidNegotiationExpiredEvent(
                bidId, announcementId, askerId, ownerId, "NEGOTIATION_TIMEOUT"));
        assertRelocked();
    }

    @Test
    void expiredOnDeparture_relocks() {
        listener.onExpiredOnDeparture(new BidExpiredOnDepartureEvent(bidId, askerId, announcementId, ownerId));
        assertRelocked();
    }

    @Test
    void cardAuthorizationExpired_relocks() {
        listener.onCardAuthorizationExpired(new BidCardAuthorizationExpiredEvent(
                bidId, announcementId, askerId, ownerId));
        assertRelocked();
    }

    @Test
    void awaitingPaymentAbandoned_relocks() {
        listener.onAwaitingPaymentAbandoned(new BidAwaitingPaymentAbandonedEvent(bidId, askerId));
        assertRelocked();
    }

    // ---- Garde-fous du service

    @Test
    void anotherLiveBidOfTheSameAskerOnTheSameAnnouncement_keepsTheBookingConversation() {
        when(bidRepository.existsByAnnouncementIdAndSenderIdAndStatusNotIn(
                eq(announcementId), eq(askerId), anyCollection())).thenReturn(true);

        assertThat(service.relockInquiry(bidId)).isFalse();

        assertStillBooking();
    }

    @Test
    void terminalStatusesPassedToTheOtherBidCheck_areTheNeverBookedOnes() {
        service.relockInquiry(bidId);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<BidStatus>> captor = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(bidRepository).existsByAnnouncementIdAndSenderIdAndStatusNotIn(
                eq(announcementId), eq(askerId), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(
                BidStatus.REJECTED, BidStatus.CANCELLED, BidStatus.EXPIRED, BidStatus.NEGOTIATION_CLOSED);
    }

    @Test
    void conversationCreatedAtAcceptance_hasNoAsker_andIsNeverRelocked() {
        ConversationEntity booking = new ConversationEntity(bidId, askerId, ownerId, "conv_" + bidId);
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(booking));

        assertThat(service.relockInquiry(bidId)).isFalse();

        assertThat(booking.getKind()).isEqualTo(ConversationKind.SENDER_TRAVELER);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void closedConversation_isNeverRelocked() {
        ReflectionTestUtils.setField(conv, "closedAt", LocalDateTime.now());
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(conv));

        assertThat(service.relockInquiry(bidId)).isFalse();

        verify(conversationRepository, never()).save(any());
    }

    @Test
    void unknownBid_returnsFalse() {
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        assertThat(service.relockInquiry(bidId)).isFalse();
    }

    @Test
    void idempotent_secondCallIsANoOp() {
        assertThat(service.relockInquiry(bidId)).isTrue();
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        assertThat(service.relockInquiry(bidId)).isFalse();

        verify(conversationRepository, times(1)).save(conv);
    }

    @Test
    void firestoreFailure_keepsTheDatabaseRelocked() {
        doThrow(new RuntimeException("firestore down")).when(firestoreService).updateConversation(any(), anyMap());

        assertThat(service.relockInquiry(bidId)).isTrue();

        assertThat(conv.getKind()).isEqualTo(ConversationKind.INQUIRY);
        verify(conversationRepository).save(conv);
    }

    @Test
    void listenerNeverThrowsToTheEventBus() {
        when(conversationRepository.findByBidId(bidId)).thenThrow(new RuntimeException("db down"));

        listener.onNegotiationExpired(new BidNegotiationExpiredEvent(
                bidId, announcementId, askerId, ownerId, "NEGOTIATION_TIMEOUT"));
        listener.onBidRejected(new BidRejectedEvent(bidId, askerId, "OTHER", announcementId, false,
                BidStatus.PENDING));
    }
}
