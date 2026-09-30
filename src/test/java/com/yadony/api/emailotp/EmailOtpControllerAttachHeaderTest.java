package com.yadony.api.emailotp;

import com.yadony.api.auth.AuthService;
import com.yadony.api.auth.dto.UserResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rattachement d'email : le jeton de reconnexion part en en-tête {@code X-Session-Token}
 * (FLUTTER-4C, Firebase révoque la session quand le back écrit les coordonnées).
 */
class EmailOtpControllerAttachHeaderTest {

    private static final String UID = "uid-1";

    private final EmailOtpService service = mock(EmailOtpService.class);
    private final AuthService authService = mock(AuthService.class);
    private final EmailOtpController controller = new EmailOtpController(service, authService);
    private final UserResponse profile = mock(UserResponse.class);

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UID, null, List.of()));
        when(authService.getProfile(UID)).thenReturn(profile);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("jeton émis → en-tête X-Session-Token + profil")
    void attach_withToken_setsHeader() {
        when(service.attachEmailToAccount(UID, "awa@example.com", "123456")).thenReturn("session-token");

        ResponseEntity<UserResponse> response = controller.attach(new com.yadony.api.emailotp.dto.EmailOtpAttachRequest("awa@example.com", "123456"));

        assertThat(response.getHeaders().getFirst("X-Session-Token")).isEqualTo("session-token");
        assertThat(response.getBody()).isSameAs(profile);
    }

    @Test
    @DisplayName("pas de jeton (Firebase absent) → pas d'en-tête, profil quand même")
    void attach_withoutToken_noHeader() {
        when(service.attachEmailToAccount(UID, "awa@example.com", "123456")).thenReturn(null);

        ResponseEntity<UserResponse> response = controller.attach(new com.yadony.api.emailotp.dto.EmailOtpAttachRequest("awa@example.com", "123456"));

        assertThat(response.getHeaders().containsKey("X-Session-Token")).isFalse();
        assertThat(response.getBody()).isSameAs(profile);
    }
}
