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
import org.springframework.http.HttpStatus;
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
        when(userRepository.findByFirebaseUid("uid-asker")).thenReturn(Optional.of(asker));
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
}
