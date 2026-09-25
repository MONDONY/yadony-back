package com.yadony.api.auth;

import com.yadony.api.auth.dto.RegisterRequest;
import com.yadony.api.auth.dto.UserResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sentry YADONY-BACK-STAGING-B : deux inscriptions simultanées du même compte Firebase
 * passaient toutes deux les lectures de {@code register} et la seconde heurtait
 * {@code uq_users_firebase_uid}, donc 500.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController.register — course à l'inscription")
class AuthControllerRegisterRaceTest {

    private static final String UID = "Bo7MDmtLuRhkkVZ1SKIxIVMbGwB3";

    @Mock AuthService authService;
    @Mock GuestClaimService guestClaimService;

    private AuthController controller() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UID, null, java.util.List.of()));
        return new AuthController(authService, guestClaimService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static RegisterRequest request() {
        return mock(RegisterRequest.class);
    }

    @Test
    @DisplayName("perdant de la course → rejeu hors transaction, le profil du gagnant est renvoyé")
    void losingRequestRetriesAndReturnsTheWinnerProfile() {
        UserResponse winner = mock(UserResponse.class);
        when(authService.register(eq(UID), any(), any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_users_firebase_uid\""))
                .thenReturn(winner);

        var response = controller().register(request());

        assertThat(response.getBody()).isSameAs(winner);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(authService, times(2)).register(eq(UID), any(), any());
    }

    @Test
    @DisplayName("une seule tentative quand rien ne collisionne")
    void happyPathCallsTheServiceOnce() {
        UserResponse created = mock(UserResponse.class);
        when(authService.register(eq(UID), any(), any())).thenReturn(created);

        assertThat(controller().register(request()).getBody()).isSameAs(created);
        verify(authService, times(1)).register(eq(UID), any(), any());
    }

    @Test
    @DisplayName("violation persistante au second essai → propagée, ce n'est plus une course")
    void secondViolationPropagates() {
        when(authService.register(eq(UID), any(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_users_stripe_account_id"));

        AuthController controller = controller();
        RegisterRequest request = request();
        assertThatThrownBy(() -> controller.register(request))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(authService, times(2)).register(eq(UID), any(), any());
    }
}
