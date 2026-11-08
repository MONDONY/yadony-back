package com.yadony.api.smsotp;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.auth.AuthService;
import com.yadony.api.smsotp.dto.SmsOtpSendRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Demandeur transmis à {@link SmsOtpService#sendOtp(String, String)} : seul un vrai compte
 * connecté peut demander un code pour un numéro encore inconnu (ajout au profil). Un
 * invité se crée sans rien prouver, il rouvrirait le pompage du 08/11/2026.
 */
class SmsOtpControllerSendCallerTest {

    private static final String PHONE = "+2250700000000";

    private final SmsOtpService service = mock(SmsOtpService.class);
    private final SmsOtpController controller = new SmsOtpController(service, mock(AuthService.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("sans jeton → aucun demandeur")
    void noAuthentication_nullCaller() {
        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(org.mockito.ArgumentMatchers.eq(PHONE), isNull());
    }

    @Test
    @DisplayName("compte connecté (email, Google, Apple) → son UID")
    void signedInAccount_passesUid() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SENDER"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, "uid-1");
    }

    @Test
    @DisplayName("compte Firebase sans rôle encore (inscription en cours) → son UID")
    void signedInWithoutRoles_passesUid() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-2", null, List.of()));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, "uid-2");
    }

    @Test
    @DisplayName("invité (session anonyme Firebase) → aucun demandeur")
    void guest_nullCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("guest-uid", null,
                        List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(org.mockito.ArgumentMatchers.eq(PHONE), isNull());
    }

    @Test
    @DisplayName("jeton anonyme Spring Security → aucun demandeur")
    void springAnonymous_nullCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(org.mockito.ArgumentMatchers.eq(PHONE), isNull());
    }

    @Test
    @DisplayName("administrateur du back-office → aucun demandeur")
    void admin_nullCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(mock(AdminPrincipal.class), null, List.of()));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(org.mockito.ArgumentMatchers.eq(PHONE), isNull());
    }

    @Test
    @DisplayName("signedInUid lit le contexte de sécurité courant")
    void signedInUid_readsContext() {
        assertThat(SmsOtpController.signedInUid()).isNull();
    }
}
