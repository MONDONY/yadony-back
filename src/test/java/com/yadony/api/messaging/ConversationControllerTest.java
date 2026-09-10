package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.PageResponse;
import com.yadony.api.common.StorageService;
import com.yadony.api.messaging.dto.ConversationResponse;
import com.yadony.api.messaging.dto.ImageUploadResponse;
import com.yadony.api.messaging.dto.ParticipantDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationService conversationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private com.yadony.api.common.BlockVisibility blockVisibility;

    @InjectMocks
    private ConversationController controller;

    private UserEntity currentUser;
    private UUID currentUserId;
    private ConversationEntity conversation;
    private UUID conversationId;
    private static final String TEST_UID = "firebase-uid-test-001";

    @BeforeEach
    void setUp() throws Exception {
        currentUserId = UUID.randomUUID();
        currentUser = new UserEntity();
        setId(currentUser, currentUserId);
        currentUser.setFirstName("Alice");
        currentUser.setLastName("Martin");

        conversationId = UUID.randomUUID();
        UUID senderId = currentUserId;
        UUID travelerId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();

        conversation = new ConversationEntity(bidId, senderId, travelerId, "conv_" + bidId);
        setId(conversation, conversationId);

        // Set up SecurityContext with the test UID as principal
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(TEST_UID, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Default mock: resolve UID → UserEntity
        when(userRepository.findByFirebaseUid(TEST_UID)).thenReturn(Optional.of(currentUser));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // Reflection helper: set the id field declared in BaseEntity
    private void setId(Object entity, UUID id) throws Exception {
        Field f = entity.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    // -------------------------------------------------------------------------
    // listArchivedConversations : même enveloppe paginée que la liste active
    // -------------------------------------------------------------------------

    @Test
    void listArchivedConversations_wrapsTheWholeListInASinglePage() {
        ConversationResponse archived = new ConversationResponse(
                conversationId, conversation.getBidId(),
                conversation.getFirestoreConversationId(),
                new ParticipantDTO(UUID.randomUUID().toString(), "Other User", null, false, null, false),
                null, LocalDateTime.now(), false,
                null, null, null, null, null, false, false);
        when(conversationService.getArchivedConversations(currentUserId)).thenReturn(List.of(archived));

        ResponseEntity<PageResponse<ConversationResponse>> response = controller.listArchivedConversations();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageResponse<ConversationResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.content()).containsExactly(archived);
        assertThat(body.page()).isZero();
        assertThat(body.totalElements()).isEqualTo(1);
        assertThat(body.last()).isTrue();
    }

    @Test
    void listArchivedConversations_emptyListStaysAPage() {
        when(conversationService.getArchivedConversations(currentUserId)).thenReturn(List.of());

        PageResponse<ConversationResponse> body = controller.listArchivedConversations().getBody();

        assertThat(body).isNotNull();
        assertThat(body.content()).isEmpty();
        assertThat(body.totalElements()).isZero();
        assertThat(body.last()).isTrue();
    }

    // -------------------------------------------------------------------------
    // listConversations_returnsPage
    // -------------------------------------------------------------------------

    @Test
    void listConversations_returnsPage() {
        Pageable pageable = PageRequest.of(0, 20);
        PageImpl<ConversationEntity> page = new PageImpl<>(List.of(conversation));

        ConversationResponse fakeResponse = new ConversationResponse(
                conversationId, conversation.getBidId(),
                conversation.getFirestoreConversationId(),
                new ParticipantDTO(UUID.randomUUID().toString(), "Other User", null, false, null, false),
                null, LocalDateTime.now(), false,
                null, null, null, null, null, false, false);

        when(conversationRepository.findByParticipant(currentUserId, pageable)).thenReturn(page);
        when(conversationService.fetchConversationMeta(anyList())).thenReturn(Map.of());
        when(conversationService.toResponse(eq(conversation), eq(currentUserId), anyMap())).thenReturn(fakeResponse);

        ResponseEntity<PageResponse<ConversationResponse>> response =
                controller.listConversations(pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).hasSize(1);
        assertThat(response.getBody().totalElements()).isEqualTo(1);
    }

    @Test
    void listConversations_batchFetchesFirestoreMeta_forAllFirestoreIds() {
        Pageable pageable = PageRequest.of(0, 20);
        PageImpl<ConversationEntity> page = new PageImpl<>(List.of(conversation));
        ConversationResponse fakeResponse = new ConversationResponse(
                conversationId, conversation.getBidId(),
                conversation.getFirestoreConversationId(),
                new ParticipantDTO(UUID.randomUUID().toString(), "Other User", null, false, null, false),
                null, LocalDateTime.now(), false,
                null, null, null, null, null, false, false);

        when(conversationRepository.findByParticipant(currentUserId, pageable)).thenReturn(page);
        when(conversationService.fetchConversationMeta(List.of(conversation.getFirestoreConversationId())))
                .thenReturn(Map.of());
        when(conversationService.toResponse(eq(conversation), eq(currentUserId), anyMap())).thenReturn(fakeResponse);

        controller.listConversations(pageable);

        verify(conversationService).fetchConversationMeta(List.of(conversation.getFirestoreConversationId()));
    }

    // -------------------------------------------------------------------------
    // Blocage : les fils masqués sortent de la liste, et on n'y poste plus
    // -------------------------------------------------------------------------

    @Test
    void listConversations_excludesThreadsWithHiddenCounterparty() {
        Pageable pageable = PageRequest.of(0, 20);
        UUID hiddenUserId = UUID.randomUUID();
        // La page renvoyée est déjà amputée : le filtrage se fait en base pour ne pas
        // fausser la pagination.
        PageImpl<ConversationEntity> page = new PageImpl<>(List.of());

        when(blockVisibility.hiddenUserIdsFor(currentUserId)).thenReturn(java.util.Set.of(hiddenUserId));
        when(conversationRepository.findByParticipantExcludingHidden(
                eq(currentUserId), eq(java.util.Set.of(hiddenUserId)), eq(pageable))).thenReturn(page);
        when(conversationService.fetchConversationMeta(anyList())).thenReturn(Map.of());

        ResponseEntity<PageResponse<ConversationResponse>> response =
                controller.listConversations(pageable);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEmpty();
        verify(conversationRepository, never()).findByParticipant(any(), any());
    }

    @Test
    void updateLastMessage_propagates404_whenCounterpartyHidden() {
        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.of(conversation));
        doThrow(new com.yadony.api.common.YadonyBusinessException(HttpStatus.NOT_FOUND,
                "not-found", "Not Found", "Ressource introuvable"))
                .when(conversationService).assertMessagingAllowed(conversation, currentUserId);

        assertThatThrownBy(() -> controller.updateLastMessage(conversationId,
                new com.yadony.api.messaging.dto.LastMessageRequest("Coucou")))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);

        verify(conversationService, never()).updateLastMessage(anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // getConversation_returns403_whenNotParticipant
    // -------------------------------------------------------------------------

    @Test
    void getConversation_returns403_whenNotParticipant() {
        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getConversation(conversationId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // -------------------------------------------------------------------------
    // getConversation_returns200_whenParticipant
    // -------------------------------------------------------------------------

    @Test
    void getConversation_returns200_whenParticipant() {
        ConversationResponse fakeResponse = new ConversationResponse(
                conversationId, conversation.getBidId(),
                conversation.getFirestoreConversationId(),
                new ParticipantDTO(UUID.randomUUID().toString(), "Other User", null, false, null, false),
                null, LocalDateTime.now(), false,
                null, null, null, null, null, false, false);

        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.of(conversation));
        when(conversationService.toResponse(eq(conversation), eq(currentUserId)))
                .thenReturn(fakeResponse);

        ResponseEntity<ConversationResponse> response =
                controller.getConversation(conversationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(conversationId);
        assertThat(response.getBody().firestoreConversationId())
                .isEqualTo(conversation.getFirestoreConversationId());
    }

    // -------------------------------------------------------------------------
    // uploadImage_returns403_whenNotParticipant
    // -------------------------------------------------------------------------

    @Test
    void uploadImage_returns403_whenNotParticipant() {
        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.empty());

        MultipartFile mockFile = mock(MultipartFile.class);

        assertThatThrownBy(() -> controller.uploadImage(conversationId, mockFile))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // -------------------------------------------------------------------------
    // uploadImage_returns422_whenFileTooLarge
    // -------------------------------------------------------------------------

    @Test
    void uploadImage_returns422_whenFileTooLarge() {
        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.of(conversation));

        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getSize()).thenReturn(6L * 1024 * 1024); // 6 MB > 5 MB limit

        assertThatThrownBy(() -> controller.uploadImage(conversationId, mockFile))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // -------------------------------------------------------------------------
    // uploadImage_returns422_whenWrongMimeType
    // -------------------------------------------------------------------------

    @Test
    void uploadImage_returns422_whenWrongMimeType() {
        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.of(conversation));

        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getSize()).thenReturn(1L * 1024 * 1024); // 1 MB — OK size
        when(mockFile.getContentType()).thenReturn("application/pdf");

        assertThatThrownBy(() -> controller.uploadImage(conversationId, mockFile))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // -------------------------------------------------------------------------
    // uploadImage_returns200_whenValid
    // -------------------------------------------------------------------------

    @Test
    void uploadImage_returns200_whenValid() throws Exception {
        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.of(conversation));

        MultipartFile mockFile = mock(MultipartFile.class);
        when(mockFile.getSize()).thenReturn(1L * 1024 * 1024); // 1 MB
        when(mockFile.getContentType()).thenReturn("image/jpeg");

        String expectedKey = "messaging/conv_bid123/12345_uuid.jpg";
        String expectedUrl = "https://s3.example.com/presigned";

        when(storageService.uploadFile(eq(mockFile), any(String.class))).thenReturn(expectedKey);
        when(storageService.generatePresignedUrl(eq(expectedKey), any())).thenReturn(expectedUrl);

        ResponseEntity<ImageUploadResponse> response =
                controller.uploadImage(conversationId, mockFile);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().s3Key()).isEqualTo(expectedKey);
        assertThat(response.getBody().presignedUrl()).isEqualTo(expectedUrl);
    }

    // -------------------------------------------------------------------------
    // updateLastMessage_returns204_whenParticipant
    // -------------------------------------------------------------------------

    @Test
    void updateLastMessage_returns204_whenParticipant() {
        com.yadony.api.messaging.dto.LastMessageRequest body =
                new com.yadony.api.messaging.dto.LastMessageRequest("Hello!");

        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.of(conversation));

        ResponseEntity<Void> response =
                controller.updateLastMessage(conversationId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(conversationService).updateLastMessage(conversation.getFirestoreConversationId(), "Hello!");
    }

    // -------------------------------------------------------------------------
    // updateLastMessage_returns403_whenNotParticipant
    // -------------------------------------------------------------------------

    @Test
    void updateLastMessage_returns403_whenNotParticipant() {
        com.yadony.api.messaging.dto.LastMessageRequest body =
                new com.yadony.api.messaging.dto.LastMessageRequest("Hello!");

        when(conversationRepository.findByIdAndParticipant(conversationId, currentUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.updateLastMessage(conversationId, body))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // -------------------------------------------------------------------------
    // getConversationByBidId_returns200_whenParticipant
    // -------------------------------------------------------------------------

    @Test
    void getConversationByBidId_returns200_whenParticipant() {
        UUID bidId = conversation.getBidId();
        ConversationResponse fakeResponse = new ConversationResponse(
                conversationId, bidId,
                conversation.getFirestoreConversationId(),
                new ParticipantDTO(UUID.randomUUID().toString(), "Other User", null, false, null, false),
                null, LocalDateTime.now(), false,
                null, null, null, null, null, false, false);

        when(conversationService.getOrCreateByBidId(bidId, currentUserId))
                .thenReturn(conversation);
        when(conversationService.toResponse(eq(conversation), eq(currentUserId)))
                .thenReturn(fakeResponse);

        ResponseEntity<ConversationResponse> response =
                controller.getConversationByBidId(bidId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().bidId()).isEqualTo(bidId);
        assertThat(response.getBody().firestoreConversationId())
                .isEqualTo(conversation.getFirestoreConversationId());
    }

    // -------------------------------------------------------------------------
    // getConversationByBidId_returns403_whenNotParticipant
    // -------------------------------------------------------------------------

    @Test
    void getConversationByBidId_returns403_whenNotParticipant() {
        UUID bidId = UUID.randomUUID();
        when(conversationService.getOrCreateByBidId(bidId, currentUserId))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Conversation not found or access denied"));

        assertThatThrownBy(() -> controller.getConversationByBidId(bidId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
