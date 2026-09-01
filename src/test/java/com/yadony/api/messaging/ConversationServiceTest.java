package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.auth.KycStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock StorageService storageService;
    @Mock BlockVisibility blockVisibility;

    ConversationService service;

    UUID bidId      = UUID.randomUUID();
    UUID senderId   = UUID.randomUUID();
    UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ConversationService(conversationRepository, firestoreService, userRepository, auditService,
                bidRepository, announcementRepository, storageService, blockVisibility);

        UserEntity sender   = mockUser(senderId,   "Alice", "Martin", "uid-sender");
        UserEntity traveler = mockUser(travelerId, "Bob",   "Dupont", "uid-traveler");
        lenient().when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    @Test
    void createConversation_persistsEntityAndCallsFirestore() {
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        ConversationEntity saved = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(conversationRepository.save(any())).thenReturn(saved);

        ConversationEntity result = service.createConversationForBid(bidId, senderId, travelerId);

        assertThat(result.getBidId()).isEqualTo(bidId);
        verify(firestoreService).createConversation(eq("conv_" + bidId), anyMap());
        verify(firestoreService).addSystemMessage(eq("conv_" + bidId), anyString());
        verify(auditService).log(eq("conversation"), any(), eq("CONVERSATION_CREATED"), eq(senderId), anyMap());
    }

    @Test
    void createConversation_isIdempotent_whenAlreadyExists() {
        ConversationEntity existing = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(existing));

        service.createConversationForBid(bidId, senderId, travelerId);

        verifyNoInteractions(firestoreService);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void toResponse_marksPhoneAvailable_whenDealActive_andNotWhenNot() {
        UserEntity traveler = mock(UserEntity.class);
        when(traveler.publicDisplayName()).thenReturn("Bob D.");
        lenient().when(traveler.getKycStatus()).thenReturn(KycStatus.VERIFIED);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);

        // Deal actif (ACCEPTED) → joignable + rôle "Voyageur".
        BidEntity acceptedBid = mockBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(acceptedBid));
        var active = service.toResponse(conv, senderId);
        assertThat(active.otherParticipant().phoneAvailable()).isTrue();
        assertThat(active.otherParticipant().role()).isEqualTo("Voyageur");
        assertThat(active.otherParticipant().kycVerified()).isTrue();

        // Deal non actif (PENDING) → non joignable.
        BidEntity pendingBid = mockBid(BidStatus.PENDING);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(pendingBid));
        var pending = service.toResponse(conv, senderId);
        assertThat(pending.otherParticipant().phoneAvailable()).isFalse();
    }

    @Test
    void toResponse_neverReadsPhoneNumber_evenWhenDealActive() {
        // Le numéro ne doit plus jamais transiter par une conversation : il s'obtient
        // uniquement via GET /bids/{bidId}/contact, au tap sur « appeler ».
        UserEntity traveler = mock(UserEntity.class);
        lenient().when(traveler.publicDisplayName()).thenReturn("Bob D.");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        // Construit hors du when(...) : imbriquer le stubbing lève UnfinishedStubbing.
        BidEntity inTransit = mockBid(BidStatus.IN_TRANSIT);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(inTransit));
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);

        var response = service.toResponse(conv, senderId);

        assertThat(response.otherParticipant().phoneAvailable()).isTrue();
        verify(traveler, never()).getFirebaseUid();
    }

    @Test
    void toResponse_marksPhoneUnavailable_whenParticipantHidesNumber() {
        // Préférence « ne pas révéler mon numéro » : le bouton d'appel disparaît du
        // header du chat, mais la conversation elle-même reste ouverte — c'est
        // précisément le canal que l'utilisateur a choisi de garder.
        UserEntity traveler = mock(UserEntity.class);
        lenient().when(traveler.publicDisplayName()).thenReturn("Bob D.");
        when(traveler.isHidePhoneNumber()).thenReturn(true);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        BidEntity accepted = mockBid(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(accepted));
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);

        var response = service.toResponse(conv, senderId);

        assertThat(response.otherParticipant().phoneAvailable()).isFalse();
        // Patronyme abrégé : ce DTO part vers l'interlocuteur, pas vers le back-office.
        assertThat(response.otherParticipant().name()).isEqualTo("Bob D.");
    }

    @Test
    void toResponse_mergesFirestoreLastMessage_whenMetaPresent() {
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        when(firestoreService.getConversationMeta(List.of("conv_" + bidId))).thenReturn(Map.of(
                "conv_" + bidId, Map.of(
                        "lastMessagePreview", "À demain !",
                        "lastMessageAt", "2026-07-14T17:03:56.739Z")));

        var response = service.toResponse(conv, senderId);

        assertThat(response.lastMessagePreview()).isEqualTo("À demain !");
        assertThat(response.lastMessageAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 7, 14, 17, 3, 56, 739_000_000));
    }

    @Test
    void toResponse_fallsBackToUpdatedAt_whenFirestoreMetaAbsent() {
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        when(firestoreService.getConversationMeta(List.of("conv_" + bidId))).thenReturn(Map.of());

        var response = service.toResponse(conv, senderId);

        assertThat(response.lastMessagePreview()).isNull();
        assertThat(response.lastMessageAt()).isEqualTo(conv.getUpdatedAt());
    }

    @Test
    void toResponse_fallsBackToUpdatedAt_whenLastMessageAtMalformed() {
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        when(firestoreService.getConversationMeta(List.of("conv_" + bidId))).thenReturn(Map.of(
                "conv_" + bidId, Map.of("lastMessageAt", "not-a-timestamp")));

        var response = service.toResponse(conv, senderId);

        assertThat(response.lastMessageAt()).isEqualTo(conv.getUpdatedAt());
    }

    @Test
    void toResponse_batchOverload_usesProvidedMap_withoutExtraFirestoreCall() {
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        Map<String, Map<String, Object>> meta = Map.of(
                "conv_" + bidId, Map.of("lastMessagePreview", "Merci beaucoup"));

        var response = service.toResponse(conv, senderId, meta);

        assertThat(response.lastMessagePreview()).isEqualTo("Merci beaucoup");
        verifyNoInteractions(firestoreService);
    }

    @Test
    void fetchConversationMeta_delegatesToFirestoreService() {
        List<String> ids = List.of("conv_a", "conv_b");
        when(firestoreService.getConversationMeta(ids)).thenReturn(Map.of());

        service.fetchConversationMeta(ids);

        verify(firestoreService).getConversationMeta(ids);
    }

    // ── Blocage : masquage symétrique et silencieux ───────────────────────────

    @Test
    void getOrCreateByBidId_returns404_whenCounterpartyHidden() {
        ConversationEntity existing = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(conversationRepository.findByBidIdAndParticipant(bidId, senderId))
                .thenReturn(Optional.of(existing));
        doThrow(new YadonyBusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                "not-found", "Not Found", "Ressource introuvable"))
                .when(blockVisibility).assertVisible(senderId, travelerId);

        assertThatThrownBy(() -> service.getOrCreateByBidId(bidId, senderId))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @Test
    void getOrCreateByBidId_returnsConversation_whenTransactionStillActive() {
        // Transaction en cours : BlockVisibility ne masque rien, le fil reste ouvert
        // des deux côtés malgré le blocage.
        ConversationEntity existing = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(conversationRepository.findByBidIdAndParticipant(bidId, senderId))
                .thenReturn(Optional.of(existing));

        ConversationEntity result = service.getOrCreateByBidId(bidId, senderId);

        assertThat(result).isSameAs(existing);
        verify(blockVisibility).assertVisible(senderId, travelerId);
    }

    @Test
    void getOrCreateByBidId_returns404_whenHidden_onCopyDeletedBySelf() {
        ConversationEntity deleted = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(conversationRepository.findByBidIdAndParticipant(bidId, travelerId))
                .thenReturn(Optional.empty());
        when(conversationRepository.findByBidIdAndParticipantIgnoreDeleted(bidId, travelerId))
                .thenReturn(Optional.of(deleted));
        doThrow(new YadonyBusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                "not-found", "Not Found", "Ressource introuvable"))
                .when(blockVisibility).assertVisible(travelerId, senderId);

        assertThatThrownBy(() -> service.getOrCreateByBidId(bidId, travelerId))
                .isInstanceOf(YadonyBusinessException.class);
    }

    /** Chemin « copie supprimée par soi-même » quand rien n'est masqué : le fil est rendu
     *  tel quel, avec son drapeau, pour que l'appelant propose la restauration. */
    @Test
    void getOrCreateByBidId_rendLaCopieSupprimee_quandRienNestMasque() {
        ConversationEntity deleted = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        when(conversationRepository.findByBidIdAndParticipant(bidId, travelerId))
                .thenReturn(Optional.empty());
        when(conversationRepository.findByBidIdAndParticipantIgnoreDeleted(bidId, travelerId))
                .thenReturn(Optional.of(deleted));

        ConversationEntity result = service.getOrCreateByBidId(bidId, travelerId);

        assertThat(result).isSameAs(deleted);
        verify(blockVisibility).assertVisible(travelerId, senderId);
    }

    /** Chemin de création : aucun fil n'existe encore et la contrepartie est masquée. */
    @Test
    void getOrCreateByBidId_refuseLaCreation_quandLaContrepartieEstMasquee() {
        UUID announcementId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcementId);
        com.yadony.api.matching.AnnouncementEntity announcement =
                new com.yadony.api.matching.AnnouncementEntity();
        announcement.setTravelerId(travelerId);

        when(conversationRepository.findByBidIdAndParticipant(bidId, senderId))
                .thenReturn(Optional.empty());
        when(conversationRepository.findByBidIdAndParticipantIgnoreDeleted(bidId, senderId))
                .thenReturn(Optional.empty());
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        doThrow(new YadonyBusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                "not-found", "Not Found", "Ressource introuvable"))
                .when(blockVisibility).assertVisible(senderId, travelerId);

        assertThatThrownBy(() -> service.getOrCreateByBidId(bidId, senderId))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        // Aucun fil ne naît entre deux comptes masqués l'un pour l'autre.
        verify(conversationRepository, never()).save(any());
    }

    /** Chemin de création quand rien n'est masqué : le fil se crée normalement. */
    @Test
    void getOrCreateByBidId_creeLeFil_quandRienNestMasque() {
        UUID announcementId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcementId);
        com.yadony.api.matching.AnnouncementEntity announcement =
                new com.yadony.api.matching.AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        ConversationEntity existing = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);

        when(conversationRepository.findByBidIdAndParticipant(bidId, senderId))
                .thenReturn(Optional.empty());
        when(conversationRepository.findByBidIdAndParticipantIgnoreDeleted(bidId, senderId))
                .thenReturn(Optional.empty());
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        // Un fil deja cree pour ce bid : createConversationForBid le rend sans repasser
        // par Firestore, ce qui garde le test centre sur la garde de blocage.
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(existing));

        ConversationEntity result = service.getOrCreateByBidId(bidId, senderId);

        assertThat(result).isSameAs(existing);
        verify(blockVisibility, atLeastOnce()).assertVisible(senderId, travelerId);
    }

    @Test
    void createConversationForBid_refuses_whenParticipantsHiddenFromEachOther() {
        doThrow(new YadonyBusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                "not-found", "Not Found", "Ressource introuvable"))
                .when(blockVisibility).assertVisible(senderId, travelerId);

        assertThatThrownBy(() -> service.createConversationForBid(bidId, senderId, travelerId))
                .isInstanceOf(YadonyBusinessException.class);

        verify(conversationRepository, never()).save(any());
        verifyNoInteractions(firestoreService);
    }

    @Test
    void assertMessagingAllowed_checksTheCounterparty_fromTheActorPointOfView() {
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);

        service.assertMessagingAllowed(conv, travelerId);

        verify(blockVisibility).assertVisible(travelerId, senderId);
    }

    @Test
    void assertMessagingAllowed_propagates404_whenCounterpartyHidden() {
        ConversationEntity conv = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        doThrow(new YadonyBusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                "not-found", "Not Found", "Ressource introuvable"))
                .when(blockVisibility).assertVisible(senderId, travelerId);

        assertThatThrownBy(() -> service.assertMessagingAllowed(conv, senderId))
                .isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void getArchivedConversations_dropsThreadsWithHiddenCounterparty() {
        UUID otherBidId = UUID.randomUUID();
        UUID hiddenTravelerId = UUID.randomUUID();
        ConversationEntity visible = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        ConversationEntity masked = new ConversationEntity(
                otherBidId, senderId, hiddenTravelerId, "conv_" + otherBidId);

        when(blockVisibility.hiddenUserIdsFor(senderId)).thenReturn(java.util.Set.of(hiddenTravelerId));
        when(conversationRepository.findArchivedByParticipant(eq(senderId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(visible, masked)));
        when(firestoreService.getConversationMeta(anyList())).thenReturn(Map.of());
        when(bidRepository.findById(any())).thenReturn(Optional.empty());

        var result = service.getArchivedConversations(senderId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).bidId()).isEqualTo(bidId);
    }

    private BidEntity mockBid(BidStatus status) {
        BidEntity b = mock(BidEntity.class);
        when(b.getStatus()).thenReturn(status);
        when(b.getWeightKg()).thenReturn(null);
        when(b.getAnnouncementId()).thenReturn(UUID.randomUUID());
        return b;
    }

    /**
     * L'entité est mockée, donc {@code publicDisplayName()} doit être stubbé explicitement :
     * stubber seulement getFirstName/getLastName laisserait le nom à null, la vraie méthode
     * n'étant jamais exécutée sur un mock.
     */
    private UserEntity mockUser(UUID id, String first, String last, String uid) {
        UserEntity u = mock(UserEntity.class);
        lenient().when(u.getId()).thenReturn(id);
        lenient().when(u.getFirstName()).thenReturn(first);
        lenient().when(u.getLastName()).thenReturn(last);
        lenient().when(u.publicDisplayName()).thenReturn(shortName(first, last));
        lenient().when(u.getFirebaseUid()).thenReturn(uid);
        return u;
    }

    /** Reproduit le format de UserEntity.publicDisplayName() : « Prénom N. ». */
    private static String shortName(String first, String last) {
        if (first == null || first.isBlank()) {
            return "user1785153600";
        }
        return (last == null || last.isBlank()) ? first : first + " " + last.charAt(0) + ".";
    }

}
