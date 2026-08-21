package com.yadony.api.auth;

import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.admin.account.AdminAuthorities;
import com.yadony.api.admin.account.AdminRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FirebaseTokenFilter — credentials storage")
class FirebaseTokenFilterTest {

    @Mock private UserLinkerService userLinkerService;
    @Mock private AdminAuthService adminAuthService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;
    @Mock private FirebaseAuth mockFirebaseAuth;
    @Mock private FirebaseToken mockToken;

    private static final String FIREBASE_UID = "uid-test-001";

    private FirebaseTokenFilter buildFilter() {
        // Default: adminAuthService returns empty (no admin) so non-admin tests are unaffected
        when(adminAuthService.resolve(any())).thenReturn(Optional.empty());
        // Default: request is not an admin route
        when(request.getRequestURI()).thenReturn("/api/some-path");
        return new FirebaseTokenFilter(userLinkerService, new ObjectMapper(), adminAuthService, false, "", "test");
    }

    private UserEntity makeUser(UserStatus status) {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        u.setFirebaseUid(FIREBASE_UID);
        u.setStatus(status);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try { Field f = c.getDeclaredField("id"); f.setAccessible(true); f.set(entity, id); return; }
                catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("user inscrit → credentials = FirebaseToken décodé")
    void registeredUser_credentialsIsDecodedToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userLinkerService.resolveAndLink(eq(FIREBASE_UID), any()))
                .thenReturn(Optional.of(makeUser(UserStatus.ACTIVE)));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = (UsernamePasswordAuthenticationToken)
                SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getCredentials()).isEqualTo(mockToken);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("user non inscrit → credentials = decoded FirebaseToken (registration flow)")
    void unregisteredUser_credentialsIsDecodedToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userLinkerService.resolveAndLink(eq(FIREBASE_UID), any()))
                .thenReturn(Optional.empty());

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = (UsernamePasswordAuthenticationToken)
                SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getCredentials()).isEqualTo(mockToken);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("uid valide sans ligne users → authentifié sans rôle, pas de 401 (l'app doit voir un 404)")
    void unregisteredUser_isAuthenticatedWithoutRoles() throws Exception {
        // Contrat dont dépend le client : token valide + utilisateur absent de la base
        // doit laisser passer la requête authentifiée, pour que /auth/me réponde 404
        // (« pas encore inscrit » → onboarding) et non 401 (« session invalide » → login).
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userLinkerService.resolveAndLink(eq(FIREBASE_UID), any()))
                .thenReturn(Optional.empty());

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo(FIREBASE_UID);
        assertThat(auth.getAuthorities()).isEmpty();
        // Aucun statut d'erreur écrit : la requête poursuit sa route
        verify(response, never()).sendError(anyInt(), anyString());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("user SUSPENDED → 403, filterChain non appelé")
    void suspendedUser_returns403() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userLinkerService.resolveAndLink(eq(FIREBASE_UID), any()))
                .thenReturn(Optional.of(makeUser(UserStatus.SUSPENDED)));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        verify(response).setStatus(SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("user BANNED → 403, filterChain non appelé")
    void bannedUser_returns403() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userLinkerService.resolveAndLink(eq(FIREBASE_UID), any()))
                .thenReturn(Optional.of(makeUser(UserStatus.BANNED)));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        verify(response).setStatus(SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("DB failure → 503, SecurityContext vidé")
    void dbFailure_returns503() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(userLinkerService.resolveAndLink(eq(FIREBASE_UID), any()))
                .thenThrow(new RuntimeException("DB down"));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        verify(response).sendError(eq(503), anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, never()).doFilter(any(), any());
    }

    // ── Bypass dev — durcissement sécurité ──────────────────────────────────

    @Test
    @DisplayName("bypass activé en profil prod → refuse de démarrer (backdoor interdit)")
    void bypassInProd_failsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new FirebaseTokenFilter(userLinkerService, new ObjectMapper(), adminAuthService,
                        true, "some-token", "prod"));
    }

    @Test
    @DisplayName("bypass activé (dev) + jeton + loopback → super-admin injecté")
    void bypass_loopbackWithToken_injectsSuperAdmin() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer secret-dev-token");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        var filter = new FirebaseTokenFilter(userLinkerService, new ObjectMapper(),
                adminAuthService, true, "secret-dev-token", "dev");
        filter.doFilterInternal(request, response, filterChain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("bypass depuis une IP distante → ignoré (pas d'injection)")
    void bypass_remoteAddr_ignored() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer secret-dev-token");
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");

        var filter = new FirebaseTokenFilter(userLinkerService, new ObjectMapper(),
                adminAuthService, true, "secret-dev-token", "dev");
        try (MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of());
            filter.doFilterInternal(request, response, filterChain);
        }

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("bypass activé mais jeton vide → inerte (fail-closed)")
    void bypass_emptyToken_inert() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer whatever");

        var filter = new FirebaseTokenFilter(userLinkerService, new ObjectMapper(),
                adminAuthService, true, "", "dev");
        try (MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of());
            filter.doFilterInternal(request, response, filterChain);
        }

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ── mustChangePassword — enforcement route côté filtre ──────────────────

    private AdminAuthorities requiredPasswordAdmin() {
        return new AdminAuthorities(AdminRole.ADMIN, Set.of(), true, "admin@yadony.com", UUID.randomUUID());
    }

    private void mockFirebaseToken() throws Exception {
        when(mockToken.getUid()).thenReturn(FIREBASE_UID);
        when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);
    }

    @Test
    @DisplayName("mustChangePassword=true + route admin quelconque → 403 PASSWORD_CHANGE_REQUIRED")
    void passwordChangeRequiredBlocksOtherAdminRoutes() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
        when(request.getContextPath()).thenReturn("/api/v1");
        when(adminAuthService.resolve(FIREBASE_UID)).thenReturn(Optional.of(requiredPasswordAdmin()));
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            mockFirebaseToken();

            new FirebaseTokenFilter(userLinkerService, new ObjectMapper(), adminAuthService, false, "", "test")
                    .doFilterInternal(request, response, filterChain);
        }

        verify(response).setStatus(SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("mustChangePassword=true + POST /admin/me/change-password → autorisé")
    void passwordChangeRequiredAllowsOwnPasswordEndpoint() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/admin/me/change-password");
        when(request.getContextPath()).thenReturn("/api/v1");
        when(adminAuthService.resolve(FIREBASE_UID)).thenReturn(Optional.of(requiredPasswordAdmin()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            mockFirebaseToken();

            new FirebaseTokenFilter(userLinkerService, new ObjectMapper(), adminAuthService, false, "", "test")
                    .doFilterInternal(request, response, filterChain);
        }

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(SC_FORBIDDEN);
    }

    @Test
    @DisplayName("mustChangePassword=true + GET /admin/me → autorisé")
    void passwordChangeRequiredAllowsOwnProfileEndpoint() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/admin/me");
        when(request.getContextPath()).thenReturn("/api/v1");
        when(adminAuthService.resolve(FIREBASE_UID)).thenReturn(Optional.of(requiredPasswordAdmin()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            mockFirebaseToken();

            new FirebaseTokenFilter(userLinkerService, new ObjectMapper(), adminAuthService, false, "", "test")
                    .doFilterInternal(request, response, filterChain);
        }

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(SC_FORBIDDEN);
    }

    @Test
    @DisplayName("mustChangePassword=false → toutes les routes admin autorisées normalement")
    void passwordChangeNotRequired_normalAccess() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(adminAuthService.resolve(FIREBASE_UID)).thenReturn(Optional.of(
                new AdminAuthorities(AdminRole.ADMIN, Set.of(), false, "admin@yadony.com", UUID.randomUUID())));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            mockFirebaseToken();

            new FirebaseTokenFilter(userLinkerService, new ObjectMapper(), adminAuthService, false, "", "test")
                    .doFilterInternal(request, response, filterChain);
        }

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(SC_FORBIDDEN);
    }

    // ── ROLE_GUEST — sessions Firebase anonymes ─────────────────────────────

    @Test
    @DisplayName("session anonyme sans ligne en base → ROLE_GUEST")
    void anonymousWithoutRow() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn("anon-uid");
        when(mockToken.getClaims())
                .thenReturn(Map.of("firebase", Map.of("sign_in_provider", "anonymous")));
        when(userLinkerService.resolveAndLink(eq("anon-uid"), any()))
                .thenReturn(Optional.empty());

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_GUEST");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("session anonyme AVEC ligne en base → ROLE_GUEST, jamais les rôles de la ligne")
    void anonymousWithRowStillGuest() throws Exception {
        UserEntity guestRow = new UserEntity();
        guestRow.setFirebaseUid("anon-uid");
        guestRow.setStatus(UserStatus.ACTIVE);
        guestRow.setRoles(Set.of(Role.SENDER, Role.TRAVELER));

        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn("anon-uid");
        when(mockToken.getClaims())
                .thenReturn(Map.of("firebase", Map.of("sign_in_provider", "anonymous")));
        when(userLinkerService.resolveAndLink(eq("anon-uid"), any()))
                .thenReturn(Optional.of(guestRow));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        // Le token fait autorité, pas la base : une ligne dont les rôles auraient
        // été écrits par erreur ne doit jamais promouvoir un invité.
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_GUEST");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("session téléphone sans ligne → rôles vides (tunnel d'inscription intact)")
    void phoneWithoutRowKeepsEmptyRoles() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(mockToken.getUid()).thenReturn("new-uid");
        when(mockToken.getClaims())
                .thenReturn(Map.of("firebase", Map.of("sign_in_provider", "phone")));
        when(userLinkerService.resolveAndLink(eq("new-uid"), any()))
                .thenReturn(Optional.empty());

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
            when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);

            buildFilter().doFilterInternal(request, response, filterChain);
        }

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).isEmpty();
        verify(filterChain).doFilter(request, response);
    }
}
