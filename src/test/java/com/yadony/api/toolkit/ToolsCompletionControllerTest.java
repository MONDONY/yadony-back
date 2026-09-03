package com.yadony.api.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.toolkit.ToolsCompletionResponse.ToolStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ToolsCompletionControllerTest {

    @Mock private ToolsCompletionService service;
    @Mock private UserRepository userRepository;

    private ToolsCompletionController controller;

    @BeforeEach
    void setUp() {
        controller = new ToolsCompletionController(service, userRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returns_completion_of_the_authenticated_user() {
        authenticate("firebase-uid-1");
        UserEntity user = new UserEntity();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", id);
        when(userRepository.findByFirebaseUid("firebase-uid-1")).thenReturn(Optional.of(user));
        ToolsCompletionResponse expected = new ToolsCompletionResponse(5, 1,
                List.of(new ToolStatus("addresses", 2, true)));
        when(service.compute(id)).thenReturn(expected);

        ResponseEntity<ToolsCompletionResponse> response = controller.getMyToolsCompletion();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(expected);
    }

    @Test
    void rejects_unauthenticated_calls_with_401() {
        assertThatThrownBy(() -> controller.getMyToolsCompletion())
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknown_firebase_uid_is_404() {
        authenticate("ghost");
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getMyToolsCompletion())
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static void authenticate(String uid) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(uid, null, List.of()));
    }
}
