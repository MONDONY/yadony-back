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

        verify(service).sendOtp(PHONE, null, null);
    }

    @Test
    @DisplayName("compte connecté (email, Google, Apple) → son UID")
    void signedInAccount_passesUid() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SENDER"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, "uid-1", null);
    }

    @Test
    @DisplayName("compte Firebase sans rôle encore (inscription en cours) → son UID")
    void signedInWithoutRoles_passesUid() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-2", null, List.of()));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, "uid-2", null);
    }

    @Test
    @DisplayName("invité (session anonyme Firebase) → aucun demandeur, mais le téléphone est identifié par sa session")
    void guest_nullCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("guest-uid", null,
                        List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, null, "guest:guest-uid");
    }

    private static final String DEVICE_ID = "3f2b8c1e-9a4d-4e7b-8c2f-1a2b3c4d5e6f";

    private static void requestWithDeviceId(String deviceId) {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader(SmsOtpController.DEVICE_ID_HEADER, deviceId);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("identifiant d'appareil de l'app → le téléphone est identifié par lui, même sans session")
    void deviceIdHeader_identifiesPhone() {
        requestWithDeviceId(DEVICE_ID);

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, null, "device:" + DEVICE_ID);
    }

    @Test
    @DisplayName("identifiant d'appareil prioritaire sur la session invitée")
    void deviceIdHeader_winsOverGuest() {
        requestWithDeviceId(DEVICE_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("guest-uid", null,
                        List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, null, "device:" + DEVICE_ID);
    }

    @Test
    @DisplayName("en-tête invalide (trop court, caractères interdits) → ignoré, repli sur la session invitée")
    void invalidDeviceIdHeader_ignored() {
        requestWithDeviceId("abc<script>");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("guest-uid", null,
                        List.of(new SimpleGrantedAuthority("ROLE_GUEST"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, null, "guest:guest-uid");
    }

    @Test
    @DisplayName("compte connecté avec identifiant d'appareil → les deux sont transmis (le service suit le compte)")
    void signedInWithDeviceId_passesBoth() {
        requestWithDeviceId(DEVICE_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SENDER"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, "uid-1", "device:" + DEVICE_ID);
    }

    @Test
    @DisplayName("jeton anonyme Spring Security → aucun demandeur")
    void springAnonymous_nullCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, null, null);
    }

    @Test
    @DisplayName("administrateur du back-office → aucun demandeur")
    void admin_nullCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(mock(AdminPrincipal.class), null, List.of()));

        controller.send(new SmsOtpSendRequest(PHONE));

        verify(service).sendOtp(PHONE, null, null);
    }

    @Test
    @DisplayName("signedInUid lit le contexte de sécurité courant")
    void signedInUid_readsContext() {
        assertThat(SmsOtpController.signedInUid()).isNull();
    }
}
