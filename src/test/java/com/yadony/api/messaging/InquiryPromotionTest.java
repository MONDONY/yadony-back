package com.yadony.api.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidNegotiationMessageKind;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.BidMaterializedEvent;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import com.yadony.api.matching.events.CashBidCreatedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Rattachement d'une conversation de questions à la demande créée par l'expéditeur. */
@ExtendWith(MockitoExtension.class)
class InquiryPromotionTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;

    ConversationService service;
    InquiryPromotionListener listener;
    ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;

    final UUID askerId = UUID.randomUUID();
    final UUID ownerId = UUID.randomUUID();
    final UUID announcementId = UUID.randomUUID();
    final UUID bidId = UUID.randomUUID();
    ConversationEntity inquiry;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, firestoreService, userRepository, auditService,
                bidRepository, announcementRepository, mock(StorageService.class), mock(BlockVisibility.class),
                mock(com.yadony.api.common.CallAvailability.class),
                mock(ConversationMediaPolicy.class), mock(MessagingImageRetentionService.class));
        listener = new InquiryPromotionListener(service, bidRepository);
        inquiry = ConversationEntity.forInquiry(askerId, announcementId, ownerId, "conv_inq_abc");
        ReflectionTestUtils.setField(inquiry, "id", UUID.randomUUID());
        lenient().when(conversationRepository.findByAskerAndAnnouncement(askerId, announcementId))
                .thenReturn(Optional.of(inquiry));
        lenient().when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        lenient().when(conversationRepository.promoteInquiryGuarded(eq(inquiry.getId()), eq(bidId), any()))
                .thenReturn(1);

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ConversationService.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ConversationService.class)).detachAppender(logs);
    }

    @Test
    void promotesTheInquiry_setsBidKindQuotaAndUpdatesFirestoreWithTheThreeFields() {
        boolean promoted = service.promoteInquiryToBid(askerId, announcementId, bidId);

        assertThat(promoted).isTrue();
        assertThat(inquiry.getFirestoreConversationId()).isEqualTo("conv_inq_abc");
        verify(conversationRepository).promoteInquiryGuarded(eq(inquiry.getId()), eq(bidId), any());
        verify(conversationRepository, never()).save(any());
        verify(firestoreService).updateConversation("conv_inq_abc",
                Map.of("kind", "SENDER_TRAVELER", "quotaLifted", true, "bidId", bidId.toString()));
        verify(auditService).log(eq("conversation"), eq(inquiry.getId()), eq("INQUIRY_PROMOTED"), eq(askerId), anyMap());
    }

    @Test
    void rowReservedOrDeletedMeanwhile_guardedUpdateReturnsZero_noFirestoreNoAudit() {
        when(conversationRepository.promoteInquiryGuarded(eq(inquiry.getId()), eq(bidId), any())).thenReturn(0);

        assertThat(service.promoteInquiryToBid(askerId, announcementId, bidId)).isFalse();

        verifyNoInteractions(firestoreService, auditService);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void noInquiry_doesNothing() {
        when(conversationRepository.findByAskerAndAnnouncement(askerId, announcementId)).thenReturn(Optional.empty());

        assertThat(service.promoteInquiryToBid(askerId, announcementId, bidId)).isFalse();

        verify(conversationRepository, never()).save(any());
        verifyNoInteractions(firestoreService, auditService);
    }

    @Test
    void alreadyPromotedThread_isLeftUntouched() {
        UUID firstBid = UUID.randomUUID();
        inquiry.promoteToBid(firstBid, LocalDateTime.now());

        assertThat(service.promoteInquiryToBid(askerId, announcementId, bidId)).isFalse();

        assertThat(inquiry.getBidId()).isEqualTo(firstBid);
        verify(conversationRepository, never()).save(any());
        verifyNoInteractions(firestoreService, auditService);
    }

    @Test
    void bidAlreadyHasItsOwnConversation_noSecondThreadIsBoundToIt() {
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(mock(ConversationEntity.class)));

        assertThat(service.promoteInquiryToBid(askerId, announcementId, bidId)).isFalse();

        assertThat(inquiry.isInquiry()).isTrue();
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void firestoreFailure_keepsTheDatabaseUpdated_andLogsTheError() {
        doThrow(new RuntimeException("firestore down")).when(firestoreService).updateConversation(any(), anyMap());

        assertThat(service.promoteInquiryToBid(askerId, announcementId, bidId)).isTrue();

        verify(conversationRepository).promoteInquiryGuarded(eq(inquiry.getId()), eq(bidId), any());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("conv_inq_abc").contains("firestore down");
        });
    }

    @Test
    void ensureFirestoreDocument_repairsAPromotedThreadWithConsistentFields() {
        inquiry.promoteToBid(bidId, LocalDateTime.now());
        UserEntity asker = user("uid-asker");
        UserEntity owner = user("uid-owner");
        when(userRepository.findById(askerId)).thenReturn(Optional.of(asker));
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(firestoreService.conversationExists("conv_inq_abc")).thenReturn(false);

        service.ensureFirestoreDocument(inquiry);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(firestoreService).createConversation(eq("conv_inq_abc"), data.capture());
        assertThat(data.getValue())
                .containsEntry("kind", "SENDER_TRAVELER")
                .containsEntry("quotaLifted", true)
                .containsEntry("bidId", bidId.toString())
                .containsEntry("askerId", "uid-asker")
                .containsEntry("announcementId", announcementId.toString());
    }

    // ── Listener ────────────────────────────────────────────────────────────

    @Test
    void bidCreatedEvent_promotesWithTheEventSenderAndAnnouncement() {
        listener.onBidCreated(new BidCreatedEvent(bidId, announcementId, ownerId, askerId, "Fatou",
                BigDecimal.ONE, "Paris → Dakar"));

        verify(conversationRepository, org.mockito.Mockito.atLeastOnce())
                .promoteInquiryGuarded(eq(inquiry.getId()), eq(bidId), any());
    }

    @Test
    void cashBidCreatedEvent_promotes_includingTheNegotiatedVariant() {
        listener.onCashBidCreated(new CashBidCreatedEvent(bidId, announcementId, ownerId, askerId, "Fatou",
                BigDecimal.ONE, "Paris → Dakar"));

        verify(conversationRepository, org.mockito.Mockito.atLeastOnce())
                .promoteInquiryGuarded(eq(inquiry.getId()), eq(bidId), any());
    }

    @Test
    void bidMaterializedEvent_resolvesSenderAndAnnouncementFromTheBid() {
        BidEntity bid = new BidEntity();
        bid.setSenderId(askerId);
        bid.setAnnouncementId(announcementId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onBidMaterialized(new BidMaterializedEvent(UUID.randomUUID(), bidId));

        verify(conversationRepository, org.mockito.Mockito.atLeastOnce())
                .promoteInquiryGuarded(eq(inquiry.getId()), eq(bidId), any());
    }

    @Test
    void bidMaterializedEvent_unknownBid_isIgnored() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener.onBidMaterialized(new BidMaterializedEvent(UUID.randomUUID(), bidId));

        assertThat(inquiry.isInquiry()).isTrue();
    }

    @Test
    void firstNegotiationProposal_promotes_butCounterOffersAndOtherKindsDoNot() {
        BidEntity bid = new BidEntity();
        bid.setSenderId(askerId);
        bid.setAnnouncementId(announcementId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        listener.onNegotiationMessage(new BidNegotiationMessagePostedEvent(bidId, announcementId, ownerId, askerId,
                BidNegotiationMessageKind.COUNTER, BigDecimal.TEN, 2));
        listener.onNegotiationMessage(new BidNegotiationMessagePostedEvent(bidId, announcementId, askerId, ownerId,
                BidNegotiationMessageKind.PROPOSAL, BigDecimal.TEN, 2));
        verify(conversationRepository, never()).promoteInquiryGuarded(any(), any(), any());

        listener.onNegotiationMessage(new BidNegotiationMessagePostedEvent(bidId, announcementId, askerId, ownerId,
                BidNegotiationMessageKind.PROPOSAL, BigDecimal.TEN, 1));
        verify(conversationRepository, org.mockito.Mockito.times(1))
                .promoteInquiryGuarded(eq(inquiry.getId()), eq(bidId), any());
    }

    @Test
    void listenerNeverThrowsToTheEventBus() {
        when(conversationRepository.findByAskerAndAnnouncement(any(), any())).thenThrow(new RuntimeException("db"));

        listener.onBidCreated(new BidCreatedEvent(bidId, announcementId, ownerId, askerId, "F", BigDecimal.ONE, "c"));
        listener.onCashBidCreated(new CashBidCreatedEvent(bidId, announcementId, ownerId, askerId, "F",
                BigDecimal.ONE, "c"));
        when(bidRepository.findById(bidId)).thenThrow(new RuntimeException("db"));
        listener.onBidMaterialized(new BidMaterializedEvent(UUID.randomUUID(), bidId));
    }

    @Test
    void listenersAreAfterCommitInTheirOwnTransaction() throws Exception {
        for (String name : new String[] {"onBidCreated", "onCashBidCreated", "onBidMaterialized", "onNegotiationMessage"}) {
            Method m = java.util.Arrays.stream(InquiryPromotionListener.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertThat(m.getAnnotation(TransactionalEventListener.class).phase())
                    .as(name).isEqualTo(TransactionPhase.AFTER_COMMIT);
            assertThat(m.getAnnotation(Transactional.class).propagation())
                    .as(name).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }

    private static UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setFirstName("Prenom");
        u.setLastName("Nom");
        return u;
    }
}
