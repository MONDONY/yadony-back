package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Rendu et réparation Firestore d'une conversation RECIPIENT_TRAVELER (lot 3C). */
@ExtendWith(MockitoExtension.class)
class ConversationServiceRecipientKindTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock StorageService storageService;
    @Mock BlockVisibility blockVisibility;

    ConversationService service;

    final UUID bidId = UUID.randomUUID();
    final UUID recipientId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, firestoreService, userRepository, auditService,
                bidRepository, announcementRepository, storageService, blockVisibility);
        UserEntity recipient = user("uid-recipient", "Fatou D.");
        UserEntity traveler = user("uid-traveler", "Moussa K.");
        lenient().when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        lenient().when(firestoreService.getConversationMeta(anyList())).thenReturn(Map.of());
        BidEntity bid = mock(BidEntity.class);
        lenient().when(bid.getStatus()).thenReturn(BidStatus.IN_TRANSIT);
        lenient().when(bid.getAnnouncementId()).thenReturn(UUID.randomUUID());
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
    }

    @Test
    void travelerSeesTheRecipient_roleDestinataire_phoneNeverAvailable() {
        ConversationEntity conv = ConversationEntity.forRecipient(bidId, recipientId, travelerId, "rconv_" + bidId);

        var response = service.toResponse(conv, travelerId);

        assertThat(response.kind()).isEqualTo("RECIPIENT_TRAVELER");
        assertThat(response.otherParticipant().id()).isEqualTo(recipientId.toString());
        assertThat(response.otherParticipant().role()).isEqualTo("Destinataire");
        assertThat(response.viewerRole()).isEqualTo("TRAVELER");
        // IN_TRANSIT révèlerait le téléphone dans une conversation expéditeur ↔ voyageur.
        assertThat(response.otherParticipant().phoneAvailable()).isFalse();
        assertThat(response.readOnly()).isFalse();
    }

    @Test
    void recipientSeesTheTraveler_roleVoyageur() {
        ConversationEntity conv = ConversationEntity.forRecipient(bidId, recipientId, travelerId, "rconv_" + bidId);

        var response = service.toResponse(conv, recipientId);

        assertThat(response.otherParticipant().id()).isEqualTo(travelerId.toString());
        assertThat(response.otherParticipant().role()).isEqualTo("Voyageur");
        assertThat(response.viewerRole()).isEqualTo("RECIPIENT");
        assertThat(response.otherParticipant().phoneAvailable()).isFalse();
    }

    @Test
    void closedConversation_isReadOnlyForTheTraveler() {
        ConversationEntity conv = ConversationEntity.forRecipient(bidId, recipientId, travelerId, "rconv_" + bidId);
        conv.close(LocalDateTime.now());

        assertThat(service.toResponse(conv, travelerId).readOnly()).isTrue();
    }

    @Test
    void senderTravelerConversation_keepsItsKindAndRole() {
        UUID senderId = recipientId;
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);

        var response = service.toResponse(conv, travelerId);

        assertThat(response.kind()).isEqualTo("SENDER_TRAVELER");
        assertThat(response.otherParticipant().role()).isEqualTo("Expéditeur");
        assertThat(response.viewerRole()).isNull();
    }

    @Test
    void repair_ofAClosedRecipientConversation_neverGivesBackAccessToTheRevokedRecipient() {
        ConversationEntity conv = ConversationEntity.forRecipient(bidId, recipientId, travelerId, "rconv_" + bidId);
        conv.close(LocalDateTime.now());
        when(firestoreService.conversationExists("rconv_" + bidId)).thenReturn(false);

        service.ensureFirestoreDocument(conv);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(firestoreService).createConversation(eq("rconv_" + bidId), data.capture());
        assertThat(data.getValue())
                .containsEntry("senderId", null)
                .containsEntry("revokedRecipientId", "uid-recipient")
                .containsEntry("travelerId", "uid-traveler")
                .containsEntry("kind", "RECIPIENT_TRAVELER");
    }

    @Test
    void repair_ofAnOpenRecipientConversation_carriesTheKind() {
        ConversationEntity conv = ConversationEntity.forRecipient(bidId, recipientId, travelerId, "rconv_" + bidId);
        when(firestoreService.conversationExists("rconv_" + bidId)).thenReturn(false);

        service.ensureFirestoreDocument(conv);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(firestoreService).createConversation(eq("rconv_" + bidId), data.capture());
        assertThat(data.getValue())
                .containsEntry("senderId", "uid-recipient")
                .containsEntry("kind", "RECIPIENT_TRAVELER")
                .doesNotContainKey("revokedRecipientId");
    }

    private static UserEntity user(String uid, String name) {
        UserEntity u = mock(UserEntity.class);
        lenient().when(u.getFirebaseUid()).thenReturn(uid);
        lenient().when(u.publicDisplayName()).thenReturn(name);
        return u;
    }
}
