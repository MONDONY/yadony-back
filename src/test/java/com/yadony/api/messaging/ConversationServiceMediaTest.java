package com.yadony.api.messaging;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.CallAvailability;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.messaging.dto.ConversationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** mediaAllowed et purge des photos à la suppression par les deux parties (FLUTTER-B4). */
@ExtendWith(MockitoExtension.class)
class ConversationServiceMediaTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock StorageService storageService;
    @Mock BlockVisibility blockVisibility;
    @Mock CallAvailability callAvailability;
    @Mock ConversationMediaPolicy mediaPolicy;
    @Mock MessagingImageRetentionService imageRetention;

    ConversationService service;
    UUID senderId = UUID.randomUUID();
    UUID travelerId = UUID.randomUUID();
    ConversationEntity conv;
    BidEntity bid;

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, firestoreService, userRepository, auditService,
                bidRepository, announcementRepository, storageService, blockVisibility, callAvailability,
                mediaPolicy, imageRetention);
        conv = new ConversationEntity(UUID.randomUUID(), senderId, travelerId, "fs_media");
        ReflectionTestUtils.setField(conv, "id", UUID.randomUUID());
        bid = new BidEntity();
        bid.setStatus(BidStatus.ACCEPTED);
        lenient().when(bidRepository.findById(conv.getBidId())).thenReturn(Optional.of(bid));
    }

    @Test
    void mediaAllowed_followsThePolicy() {
        when(mediaPolicy.check(conv, senderId, bid, null)).thenReturn(Optional.empty());
        assertThat(service.toResponse(conv, senderId, Map.of()).mediaAllowed()).isTrue();

        when(mediaPolicy.check(conv, senderId, bid, null))
                .thenReturn(Optional.of(ConversationMediaPolicy.Denial.MESSAGING_MUTED));
        assertThat(service.toResponse(conv, senderId, Map.of()).mediaAllowed()).isFalse();
    }

    @Test
    void mediaAllowed_falseWithoutAskingThePolicy_outsideCandidateStatuses() {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        assertThat(service.toResponse(conv, senderId, Map.of()).mediaAllowed()).isFalse();
        verify(mediaPolicy, never()).check(any(), any(), any(BidEntity.class), any());
    }

    @Test
    void mediaAllowed_asksThePolicy_duringTheReturnOfACancelledParcel() {
        bid.setStatus(BidStatus.CANCELLED);
        bid.setReturnDeadline(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(2));
        when(mediaPolicy.check(conv, senderId, bid, null)).thenReturn(Optional.empty());
        assertThat(service.toResponse(conv, senderId, Map.of()).mediaAllowed()).isTrue();
    }

    @Test
    void mediaAllowed_falseWithoutAskingThePolicy_cancelledWithoutReturn() {
        bid.setStatus(BidStatus.CANCELLED);
        assertThat(service.toResponse(conv, senderId, Map.of()).mediaAllowed()).isFalse();
        verify(mediaPolicy, never()).check(any(), any(), any(BidEntity.class), any());
    }

    @Test
    void mediaAllowed_falseWithoutBid_andWhenThePolicyFails() {
        when(bidRepository.findById(conv.getBidId())).thenReturn(Optional.empty());
        assertThat(service.toResponse(conv, senderId, Map.of()).mediaAllowed()).isFalse();

        when(bidRepository.findById(conv.getBidId())).thenReturn(Optional.of(bid));
        when(mediaPolicy.check(any(), any(), any(BidEntity.class), any())).thenThrow(new RuntimeException("db"));
        assertThat(service.toResponse(conv, senderId, Map.of()).mediaAllowed()).isFalse();
    }

    @Test
    void legacyResponseConstructor_defaultsMediaAllowedToFalse() {
        ConversationResponse r = new ConversationResponse(UUID.randomUUID(), UUID.randomUUID(), "fs", null, null,
                null, false, null, null, null, null, null, false, false, "SENDER_TRAVELER", null, true, true);
        assertThat(r.mediaAllowed()).isFalse();
        assertThat(r.notificationsMuted()).isTrue();
    }

    @Test
    void deleteByBothParties_purgesPhotos_beforeDroppingTheConversation() {
        conv.deleteForUser(senderId);
        when(conversationRepository.findByIdAndParticipant(conv.getId(), travelerId)).thenReturn(Optional.of(conv));

        service.deleteConversation(conv.getId(), travelerId);

        InOrder order = inOrder(imageRetention, conversationRepository);
        order.verify(imageRetention).purgeConversation(conv, travelerId);
        order.verify(conversationRepository).delete(conv);
    }

    @Test
    void deleteByBothParties_stillPurgesTheConversation_whenPhotoPurgeFails() {
        conv.deleteForUser(senderId);
        when(conversationRepository.findByIdAndParticipant(conv.getId(), travelerId)).thenReturn(Optional.of(conv));
        when(imageRetention.purgeConversation(any(), any())).thenThrow(new RuntimeException("R2"));

        service.deleteConversation(conv.getId(), travelerId);

        verify(conversationRepository).delete(conv);
        verify(firestoreService).purgeConversation("fs_media");
    }

    @Test
    void deleteByOneParty_keepsPhotos() {
        when(conversationRepository.findByIdAndParticipant(conv.getId(), senderId)).thenReturn(Optional.of(conv));
        service.deleteConversation(conv.getId(), senderId);
        verify(imageRetention, never()).purgeConversation(any(), any());
    }
}
