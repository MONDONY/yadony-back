package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.reception.BidRecipientLinkEntity;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.matching.reception.ReceptionLinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecipientConversationServiceTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRecipientLinkRepository linkRepository;
    @Mock BlockVisibility blockVisibility;

    RecipientConversationService service;

    final UUID bidId = UUID.randomUUID();
    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();
    final UUID recipientId = UUID.randomUUID();
    final UUID announcementId = UUID.randomUUID();
    BidEntity bid;

    @BeforeEach
    void setUp() {
        service = new RecipientConversationService(conversationRepository, firestoreService, userRepository,
                auditService, bidRepository, announcementRepository, linkRepository, blockVisibility,
                Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(senderId);
        bid.setStatus(BidStatus.IN_TRANSIT);
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        lenient().when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        UserEntity recipient = user(recipientId, "uid-recipient", "Fatou D.");
        UserEntity traveler = user(travelerId, "uid-traveler", "Moussa K.");
        lenient().when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        lenient().when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(conversationRepository.findOpenRecipientConversations(bidId)).thenReturn(List.of());
        lenient().when(conversationRepository.findByFirestoreConversationId(anyString())).thenReturn(Optional.empty());
    }

    // ── Création ────────────────────────────────────────────────────────────

    @Test
    void traveler_createsTheConversation_recipientIsParticipantA() {
        link(ReceptionLinkStatus.CONFIRMED);

        ConversationEntity conv = service.getOrCreate(bidId, travelerId);

        assertThat(conv.isRecipientConversation()).isTrue();
        assertThat(conv.getKind()).isEqualTo(ConversationKind.RECIPIENT_TRAVELER);
        assertThat(conv.participantAId()).isEqualTo(recipientId);
        assertThat(conv.getTravelerId()).isEqualTo(travelerId);
        assertThat(conv.getFirestoreConversationId()).isEqualTo("rconv_" + bidId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(firestoreService).createConversation(eq("rconv_" + bidId), data.capture());
        assertThat(data.getValue())
                .containsEntry("senderId", "uid-recipient")
                .containsEntry("travelerId", "uid-traveler")
                .containsEntry("kind", "RECIPIENT_TRAVELER")
                .containsEntry("bidId", bidId.toString())
                .containsEntry("senderName", "Fatou D.")
                .containsEntry("travelerName", "Moussa K.");
        verify(firestoreService).addSystemMessage(eq("rconv_" + bidId), anyString());
        verify(auditService).log(eq("conversation"), any(), eq("CONVERSATION_CREATED"), eq(travelerId), anyMap());
        verify(blockVisibility).assertVisible(travelerId, recipientId);
    }

    @Test
    void recipient_createsTheConversation() {
        link(ReceptionLinkStatus.CONFIRMED);

        ConversationEntity conv = service.getOrCreate(bidId, recipientId);

        assertThat(conv.participantAId()).isEqualTo(recipientId);
        verify(blockVisibility).assertVisible(recipientId, travelerId);
    }

    @Test
    void existingOpenConversation_isReturned_withoutCreatingAnother() {
        link(ReceptionLinkStatus.CONFIRMED);
        ConversationEntity existing = ConversationEntity.forRecipient(bidId, recipientId, travelerId, "rconv_" + bidId);
        when(conversationRepository.findOpenRecipientConversations(bidId)).thenReturn(List.of(existing));

        assertThat(service.getOrCreate(bidId, recipientId)).isSameAs(existing);
        verify(conversationRepository, never()).save(any());
        verifyNoInteractions(firestoreService);
    }

    @Test
    void existingConversation_isReturned_evenOnceTheParcelIsCancelled() {
        bid.setStatus(BidStatus.CANCELLED);
        link(ReceptionLinkStatus.CONFIRMED);
        ConversationEntity existing = ConversationEntity.forRecipient(bidId, recipientId, travelerId, "rconv_" + bidId);
        when(conversationRepository.findOpenRecipientConversations(bidId)).thenReturn(List.of(existing));

        assertThat(service.getOrCreate(bidId, travelerId)).isSameAs(existing);
    }

    @Test
    void completedParcel_stillOpensAConversation() {
        bid.setStatus(BidStatus.COMPLETED);
        link(ReceptionLinkStatus.CONFIRMED);

        assertThat(service.getOrCreate(bidId, travelerId).isRecipientConversation()).isTrue();
    }

    @Test
    void cancelledParcel_withoutConversation_409() {
        bid.setStatus(BidStatus.CANCELLED);
        link(ReceptionLinkStatus.CONFIRMED);

        assertThatThrownBy(() -> service.getOrCreate(bidId, travelerId))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus().value()).isEqualTo(409));
    }

    // ── Refus ───────────────────────────────────────────────────────────────

    @Test
    void sender_isForbidden() {
        link(ReceptionLinkStatus.CONFIRMED);
        assertForbidden(senderId);
    }

    @Test
    void thirdParty_isForbidden() {
        link(ReceptionLinkStatus.CONFIRMED);
        assertForbidden(UUID.randomUUID());
    }

    @Test
    void pendingLink_isForbidden_forTravelerAndRecipient() {
        link(ReceptionLinkStatus.PENDING);
        assertForbidden(travelerId);
        assertForbidden(recipientId);
    }

    @Test
    void noLink_isForbidden() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        assertForbidden(travelerId);
    }

    @Test
    void unknownBid_404() {
        UUID unknown = UUID.randomUUID();
        when(bidRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrCreate(unknown, travelerId))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus().value()).isEqualTo(404));
    }

    // ── Changement de destinataire ─────────────────────────────────────────

    @Test
    void staleConversationWithOldRecipient_isClosed_andANewOneGetsADistinctFirestoreId() {
        UUID oldRecipient = UUID.randomUUID();
        link(ReceptionLinkStatus.CONFIRMED);
        ConversationEntity stale = ConversationEntity.forRecipient(bidId, oldRecipient, travelerId, "rconv_" + bidId);
        when(conversationRepository.findOpenRecipientConversations(bidId)).thenReturn(List.of(stale));
        when(conversationRepository.countRecipientConversations(bidId)).thenReturn(1L);
        UserEntity old = user(oldRecipient, "uid-old", "Old R.");
        when(userRepository.findByIdIncludingDeleted(oldRecipient)).thenReturn(Optional.of(old));

        ConversationEntity fresh = service.getOrCreate(bidId, travelerId);

        assertThat(stale.isClosed()).isTrue();
        verify(firestoreService).revokeRecipient("rconv_" + bidId, "uid-old");
        assertThat(fresh).isNotSameAs(stale);
        assertThat(fresh.participantAId()).isEqualTo(recipientId);
        assertThat(fresh.getFirestoreConversationId()).isEqualTo("rconv_" + bidId + "_2");
    }

    @Test
    void firestoreIdSkipsAnIdAlreadyTaken() {
        link(ReceptionLinkStatus.CONFIRMED);
        when(conversationRepository.countRecipientConversations(bidId)).thenReturn(1L);
        when(conversationRepository.findByFirestoreConversationId("rconv_" + bidId + "_2"))
                .thenReturn(Optional.of(new ConversationEntity()));

        assertThat(service.getOrCreate(bidId, travelerId).getFirestoreConversationId())
                .isEqualTo("rconv_" + bidId + "_3");
    }

    @Test
    void revokeStale_closesTheConversationOfARecipientWithoutConfirmedLink() {
        UUID oldRecipient = UUID.randomUUID();
        ConversationEntity open = ConversationEntity.forRecipient(bidId, oldRecipient, travelerId, "rconv_" + bidId);
        when(conversationRepository.findOpenRecipientConversations(bidId)).thenReturn(List.of(open));
        when(linkRepository.existsByBidIdAndRecipientUserIdAndStatus(bidId, oldRecipient, ReceptionLinkStatus.CONFIRMED))
                .thenReturn(false);
        UserEntity old = user(oldRecipient, "uid-old", "Old R.");
        when(userRepository.findByIdIncludingDeleted(oldRecipient)).thenReturn(Optional.of(old));

        assertThat(service.revokeStale(bidId)).isEqualTo(1);

        assertThat(open.isClosed()).isTrue();
        assertThat(open.isReadOnlyFor(travelerId)).isTrue();
        verify(conversationRepository).save(open);
        verify(firestoreService).revokeRecipient("rconv_" + bidId, "uid-old");
        verify(auditService).log(eq("conversation"), any(), eq("RECIPIENT_CONVERSATION_CLOSED"), isNull(), anyMap());
    }

    @Test
    void revokeStale_keepsTheConversationOfTheStillConfirmedRecipient() {
        ConversationEntity open = ConversationEntity.forRecipient(bidId, recipientId, travelerId, "rconv_" + bidId);
        when(conversationRepository.findOpenRecipientConversations(bidId)).thenReturn(List.of(open));
        when(linkRepository.existsByBidIdAndRecipientUserIdAndStatus(bidId, recipientId, ReceptionLinkStatus.CONFIRMED))
                .thenReturn(true);

        assertThat(service.revokeStale(bidId)).isZero();
        assertThat(open.isClosed()).isFalse();
        verifyNoInteractions(firestoreService);
    }

    @Test
    void revokeStale_firestoreFailure_stillClosesInPostgres() {
        UUID oldRecipient = UUID.randomUUID();
        ConversationEntity open = ConversationEntity.forRecipient(bidId, oldRecipient, travelerId, "rconv_" + bidId);
        when(conversationRepository.findOpenRecipientConversations(bidId)).thenReturn(List.of(open));
        when(userRepository.findByIdIncludingDeleted(oldRecipient)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("down")).when(firestoreService).revokeRecipient(anyString(), any());

        assertThat(service.revokeStale(bidId)).isEqualTo(1);
        assertThat(open.isClosed()).isTrue();
    }

    // ── Aides ───────────────────────────────────────────────────────────────

    private void assertForbidden(UUID caller) {
        assertThatThrownBy(() -> service.getOrCreate(bidId, caller))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getErrorCode()).isEqualTo("recipient-conversation-forbidden");
                });
        verify(conversationRepository, never()).save(any());
    }

    private void link(ReceptionLinkStatus status) {
        BidRecipientLinkEntity l = new BidRecipientLinkEntity(bidId, recipientId);
        if (status != ReceptionLinkStatus.PENDING) {
            l.respond(status, OffsetDateTime.now());
        }
        lenient().when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(l));
    }

    private static UserEntity user(UUID id, String uid, String name) {
        UserEntity u = mock(UserEntity.class);
        lenient().when(u.getId()).thenReturn(id);
        lenient().when(u.getFirebaseUid()).thenReturn(uid);
        lenient().when(u.publicDisplayName()).thenReturn(name);
        return u;
    }
}
