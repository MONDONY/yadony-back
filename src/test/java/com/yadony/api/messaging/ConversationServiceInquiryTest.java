package com.yadony.api.messaging;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Ouverture d'une conversation de questions avant réservation. */
@ExtendWith(MockitoExtension.class)
class ConversationServiceInquiryTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock StorageService storageService;
    @Mock BlockVisibility blockVisibility;
    @Mock com.yadony.api.common.CallAvailability callAvailability;

    ConversationService service;

    final UUID askerId = UUID.randomUUID();
    final UUID ownerId = UUID.randomUUID();
    final UUID announcementId = UUID.randomUUID();
    UserEntity asker;
    UserEntity owner;
    AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, firestoreService, userRepository, auditService,
                bidRepository, announcementRepository, storageService, blockVisibility, callAvailability,
                mock(ConversationMediaPolicy.class), mock(MessagingImageRetentionService.class));
        asker = user("uid-asker", "Fatou D.", KycStatus.VERIFIED);
        owner = user("uid-owner", "Moussa K.", KycStatus.VERIFIED);
        lenient().when(userRepository.findById(askerId)).thenReturn(Optional.of(asker));
        lenient().when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        announcement = mock(AnnouncementEntity.class);
        lenient().when(announcement.getTravelerId()).thenReturn(ownerId);
        lenient().when(announcement.getStatus()).thenReturn(AnnouncementStatus.ACTIVE);
        lenient().when(announcement.getDepartureDate()).thenReturn(LocalDate.now().plusDays(10));
        lenient().when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        lenient().when(conversationRepository.findByAskerAndAnnouncement(askerId, announcementId))
                .thenReturn(Optional.empty());
        lenient().when(conversationRepository.saveAndFlush(any(ConversationEntity.class))).thenAnswer(inv -> {
            ConversationEntity c = inv.getArgument(0);
            if (c.getId() == null) {
                ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            }
            return c;
        });
    }

    @Test
    void createsInquiryAndFirestoreDocument() {
        ConversationEntity conv = service.getOrCreateInquiry(askerId, announcementId);

        assertThat(conv.isInquiry()).isTrue();
        assertThat(conv.getAskerId()).isEqualTo(askerId);
        assertThat(conv.getAnnouncementId()).isEqualTo(announcementId);
        assertThat(conv.getFirestoreConversationId()).isEqualTo("conv_inq_" + conv.getId());

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(firestoreService).createConversation(eq("conv_inq_" + conv.getId()), data.capture());
        assertThat(data.getValue())
                .containsEntry("senderId", "uid-asker")
                .containsEntry("travelerId", "uid-owner")
                .containsEntry("askerId", "uid-asker")
                .containsEntry("kind", "INQUIRY")
                .containsEntry("quotaLifted", false)
                .containsEntry("announcementId", announcementId.toString())
                .containsEntry("senderName", "Fatou D.")
                .containsEntry("travelerName", "Moussa K.")
                .containsKeys("createdAt", "lastMessageAt", "lastMessagePreview");
        verify(firestoreService, never()).addSystemMessage(anyString(), anyString());
        verify(auditService).log(eq("conversation"), eq(conv.getId()), eq("INQUIRY_CREATED"), eq(askerId), anyMap());
        verify(blockVisibility).isHidden(askerId, ownerId);
    }

    @Test
    void idempotent_secondCallReturnsExistingWithoutSecondSave() {
        ConversationEntity first = service.getOrCreateInquiry(askerId, announcementId);
        when(conversationRepository.findByAskerAndAnnouncement(askerId, announcementId))
                .thenReturn(Optional.of(first));

        ConversationEntity second = service.getOrCreateInquiry(askerId, announcementId);

        assertThat(second).isSameAs(first);
        verify(conversationRepository, times(1)).saveAndFlush(any(ConversationEntity.class));
        verify(firestoreService, times(1)).createConversation(anyString(), anyMap());
    }

    @Test
    void returnsExistingConversationEvenIfAlreadyPromotedToBid() {
        ConversationEntity promoted = ConversationEntity.forInquiry(askerId, announcementId, ownerId, "conv_x");
        promoted.promoteToBid(UUID.randomUUID(), java.time.LocalDateTime.now());
        when(conversationRepository.findByAskerAndAnnouncement(askerId, announcementId))
                .thenReturn(Optional.of(promoted));

        assertThat(service.getOrCreateInquiry(askerId, announcementId)).isSameAs(promoted);
        verify(conversationRepository, never()).saveAndFlush(any(ConversationEntity.class));
    }

    @Test
    void firestoreFailureDoesNotBreakCreation() {
        doThrow(new RuntimeException("boom")).when(firestoreService).createConversation(anyString(), anyMap());

        assertThat(service.getOrCreateInquiry(askerId, announcementId)).isNotNull();
        verify(conversationRepository, times(1)).saveAndFlush(any(ConversationEntity.class));
        verify(auditService).log(eq("conversation"), any(UUID.class), eq("INQUIRY_CREATED"), eq(askerId), anyMap());
    }

    @Test
    void existingThreadWithHiddenCounterpartIsSilent404() {
        ConversationEntity existing = ConversationEntity.forInquiry(askerId, announcementId, ownerId, "conv_x");
        when(conversationRepository.findByAskerAndAnnouncement(askerId, announcementId))
                .thenReturn(Optional.of(existing));
        doThrow(YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "conversation-not-found", "problem.conversation-not-found"))
                .when(blockVisibility).assertVisible(askerId, ownerId);

        assertThatThrownBy(() -> service.getOrCreateInquiry(askerId, announcementId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void concurrentDoubleCallReturnsTheWinningThread() {
        ConversationEntity winner = ConversationEntity.forInquiry(askerId, announcementId, ownerId, "conv_w");
        when(conversationRepository.findByAskerAndAnnouncement(askerId, announcementId))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(conversationRepository.saveAndFlush(any(ConversationEntity.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("conversations_inquiry_active_idx"));

        assertThat(service.getOrCreateInquiry(askerId, announcementId)).isSameAs(winner);
        verify(firestoreService, never()).createConversation(anyString(), anyMap());
    }

    @Test
    void refusesWhenAskerMissing() {
        when(userRepository.findById(askerId)).thenReturn(Optional.empty());

        assertError(HttpStatus.FORBIDDEN, "kyc-required");
    }

    @Test
    void refusesWhenOwnerAccountDeleted() {
        when(userRepository.findById(ownerId)).thenReturn(Optional.empty());

        assertError(HttpStatus.NOT_FOUND, "conversation-counterpart-deleted");
    }

    @Test
    void draftAnnouncementIsSilent404() {
        when(announcement.getStatus()).thenReturn(AnnouncementStatus.DRAFT);

        assertError(HttpStatus.NOT_FOUND, "announcement-not-found");
    }

    @Test
    void fullAnnouncementIsClosed() {
        when(announcement.getStatus()).thenReturn(AnnouncementStatus.FULL);

        assertError(HttpStatus.UNPROCESSABLE_ENTITY, "inquiry-announcement-closed");
    }

    @Test
    void inProgressAnnouncementIsClosed() {
        when(announcement.getStatus()).thenReturn(AnnouncementStatus.IN_PROGRESS);

        assertError(HttpStatus.UNPROCESSABLE_ENTITY, "inquiry-announcement-closed");
    }

    @Test
    void repairRecreatesInquiryFieldsInFirestore() {
        ConversationEntity conv = ConversationEntity.forInquiry(askerId, announcementId, ownerId, "conv_inq_r");
        when(firestoreService.conversationExists("conv_inq_r")).thenReturn(false);

        service.ensureFirestoreDocument(conv);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(firestoreService).createConversation(eq("conv_inq_r"), data.capture());
        assertThat(data.getValue())
                .containsEntry("kind", "INQUIRY")
                .containsEntry("askerId", "uid-asker")
                .containsEntry("quotaLifted", false)
                .containsEntry("announcementId", announcementId.toString())
                .containsEntry("senderId", "uid-asker")
                .containsEntry("travelerId", "uid-owner");
    }

    @Test
    void repairOfOrdinaryConversationDoesNotAddInquiryFields() {
        ConversationEntity conv = new ConversationEntity(UUID.randomUUID(), askerId, ownerId, "conv_o");
        when(firestoreService.conversationExists("conv_o")).thenReturn(false);

        service.ensureFirestoreDocument(conv);

        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(firestoreService).createConversation(eq("conv_o"), data.capture());
        assertThat(data.getValue()).doesNotContainKeys("kind", "askerId", "quotaLifted", "announcementId");
    }

    @Test
    void refusesWhenKycNotVerified() {
        UserEntity unverified = user("uid-asker", "Fatou D.", KycStatus.PENDING);
        when(userRepository.findById(askerId)).thenReturn(Optional.of(unverified));

        assertError(HttpStatus.FORBIDDEN, "kyc-required");
    }

    @Test
    void refusesWhenMessagingMuted() {
        when(asker.isMessagingMuted(any(Instant.class))).thenReturn(true);

        assertError(HttpStatus.FORBIDDEN, "messaging-muted");
    }

    @Test
    void refusesOwnAnnouncement() {
        when(announcement.getTravelerId()).thenReturn(askerId);

        assertError(HttpStatus.FORBIDDEN, "inquiry-own-announcement");
    }

    @Test
    void refusesUnknownAnnouncement() {
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        assertError(HttpStatus.NOT_FOUND, "announcement-not-found");
    }

    @Test
    void refusesOutOfMarketAnnouncement() {
        when(announcement.getStatus()).thenReturn(AnnouncementStatus.CANCELLED);

        assertError(HttpStatus.UNPROCESSABLE_ENTITY, "inquiry-announcement-closed");
    }

    @Test
    void refusesPastDeparture() {
        when(announcement.getDepartureDate()).thenReturn(LocalDate.now().minusDays(1));

        assertError(HttpStatus.UNPROCESSABLE_ENTITY, "inquiry-announcement-closed");
    }

    @Test
    void refusesWhenCounterpartIsHidden() {
        when(blockVisibility.isHidden(askerId, ownerId)).thenReturn(true);

        assertError(HttpStatus.NOT_FOUND, "announcement-not-found");
    }

    private void assertError(HttpStatus status, String code) {
        assertThatThrownBy(() -> service.getOrCreateInquiry(askerId, announcementId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getErrorCode()).isEqualTo(code);
                });
        verify(conversationRepository, never()).saveAndFlush(any(ConversationEntity.class));
    }

    private static UserEntity user(String uid, String name, KycStatus kyc) {
        UserEntity u = mock(UserEntity.class);
        lenient().when(u.getFirebaseUid()).thenReturn(uid);
        lenient().when(u.publicDisplayName()).thenReturn(name);
        lenient().when(u.getKycStatus()).thenReturn(kyc);
        return u;
    }
}
