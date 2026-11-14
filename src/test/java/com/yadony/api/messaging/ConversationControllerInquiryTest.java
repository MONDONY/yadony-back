package com.yadony.api.messaging;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.messaging.dto.ConversationResponse;
import com.yadony.api.messaging.dto.InquiryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import com.yadony.api.common.PageResponse;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** {@code POST /conversations/inquiry} : chemin du contrôleur, dont la reprise après course. */
@ExtendWith(MockitoExtension.class)
class ConversationControllerInquiryTest {

    @Mock ConversationRepository conversationRepository;
    @Mock ConversationService conversationService;
    @Mock UserRepository userRepository;
    @Mock com.yadony.api.common.BlockVisibility blockVisibility;
    @Mock InquiryCapability inquiryCapability;
    @InjectMocks ConversationController controller;

    final UUID askerId = UUID.randomUUID();
    final UUID announcementId = UUID.randomUUID();
    ConversationEntity conv;
    ConversationResponse response;

    @BeforeEach
    void setUp() {
        UserEntity asker = new UserEntity();
        ReflectionTestUtils.setField(asker, "id", askerId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-asker", null, List.of()));
        lenient().when(userRepository.findByFirebaseUid("uid-asker")).thenReturn(Optional.of(asker));
        conv = ConversationEntity.forInquiry(askerId, announcementId, UUID.randomUUID(), "conv_inq_x");
        response = mock(ConversationResponse.class);
        lenient().when(conversationService.toResponse(conv, askerId)).thenReturn(response);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void openInquiry_returnsTheConversationResponse() {
        when(conversationService.getOrCreateInquiry(askerId, announcementId)).thenReturn(conv);

        ResponseEntity<ConversationResponse> r = controller.openInquiry(new InquiryRequest(announcementId));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isSameAs(response);
        verify(conversationService, times(1)).getOrCreateInquiry(askerId, announcementId);
    }

    @Test
    void openInquiry_raceOnUniqueIndex_retriesOnceAndReturnsTheWinner() {
        when(conversationService.getOrCreateInquiry(askerId, announcementId))
                .thenThrow(new DataIntegrityViolationException("conversations_inquiry_active_idx"))
                .thenReturn(conv);

        ResponseEntity<ConversationResponse> r = controller.openInquiry(new InquiryRequest(announcementId));

        assertThat(r.getBody()).isSameAs(response);
        verify(conversationService, times(2)).getOrCreateInquiry(askerId, announcementId);
    }

    @Test
    void openInquiry_secondViolation_isNotSwallowed() {
        when(conversationService.getOrCreateInquiry(askerId, announcementId))
                .thenThrow(new DataIntegrityViolationException("x"));

        assertThatThrownBy(() -> controller.openInquiry(new InquiryRequest(announcementId)))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(conversationService, times(2)).getOrCreateInquiry(askerId, announcementId);
    }

    // ── Activation explicite : une app qui n'annonce pas `inquiry` ne voit jamais une INQUIRY ──

    private ConversationEntity inquiryWithId() {
        ReflectionTestUtils.setField(conv, "id", UUID.randomUUID());
        return conv;
    }

    @Test
    void list_withoutCapability_dropsInquiryThreads() {
        ConversationEntity inquiry = inquiryWithId();
        ConversationEntity plain = new ConversationEntity(UUID.randomUUID(), askerId, UUID.randomUUID(), "conv_plain");
        when(inquiryCapability.enabled()).thenReturn(false);
        when(blockVisibility.hiddenUserIdsFor(askerId)).thenReturn(java.util.Set.of());
        when(conversationRepository.findByParticipant(askerId, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(inquiry, plain)));
        when(conversationService.fetchConversationMeta(org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Map.of());
        when(conversationService.toResponses(org.mockito.ArgumentMatchers.anyList(), eq(askerId),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of());

        controller.listConversations(Pageable.ofSize(20));

        verify(conversationService).toResponses(eq(List.of(plain)), eq(askerId), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void list_withCapability_keepsInquiryThreads() {
        ConversationEntity inquiry = inquiryWithId();
        when(inquiryCapability.enabled()).thenReturn(true);
        when(blockVisibility.hiddenUserIdsFor(askerId)).thenReturn(java.util.Set.of());
        when(conversationRepository.findByParticipant(askerId, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(inquiry)));
        when(conversationService.fetchConversationMeta(org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.Map.of());
        when(conversationService.toResponses(org.mockito.ArgumentMatchers.anyList(), eq(askerId),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(List.of());

        controller.listConversations(Pageable.ofSize(20));

        verify(conversationService).toResponses(eq(List.of(inquiry)), eq(askerId), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void archivedList_withoutCapability_dropsInquiryResponses() {
        ConversationResponse inq = mock(ConversationResponse.class);
        when(inq.kind()).thenReturn("INQUIRY");
        ConversationResponse plain = mock(ConversationResponse.class);
        when(plain.kind()).thenReturn("SENDER_TRAVELER");
        when(inquiryCapability.enabled()).thenReturn(false);
        when(conversationService.getArchivedConversations(askerId)).thenReturn(List.of(inq, plain));

        PageResponse<ConversationResponse> body = controller.listArchivedConversations().getBody();

        assertThat(body.content()).containsExactly(plain);
    }

    @Test
    void byIdEndpoints_withoutCapability_answerLikeAnUnknownConversation() {
        ConversationEntity inquiry = inquiryWithId();
        UUID id = inquiry.getId();
        when(inquiryCapability.enabled()).thenReturn(false);
        when(conversationRepository.findById(id)).thenReturn(Optional.of(inquiry));

        List<org.junit.jupiter.api.function.Executable> calls = List.of(
                () -> controller.getConversation(id),
                () -> controller.updateLastMessage(id, new com.yadony.api.messaging.dto.LastMessageRequest("x")),
                () -> controller.deleteConversation(id),
                () -> controller.archiveConversation(id),
                () -> controller.unarchiveConversation(id),
                () -> controller.muteConversation(id),
                () -> controller.unmuteConversation(id),
                () -> controller.restoreConversation(id));
        for (var call : calls) {
            ResponseStatusException e = org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class, call);
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        verifyNoInteractions(conversationService);
    }
}
