package com.yadony.api.auth;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.admin.account.AdminAuthorities;
import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration-style Mockito tests for Task 5 — admin authority mapping in FirebaseTokenFilter.
 *
 * Verifies:
 * 1. SUPPORT admin token → SecurityContext has AdminPrincipal + USER_VIEW authority
 * 2. DISABLED admin (resolve → empty) on /admin/** → 403, filter chain not called
 * 3. Non-admin token on /admin/** → 403, filter chain not called
 * 4. SUPPORT token lacks PAYMENT_REFUND → assertion on authorities
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminSecurityIT — FirebaseTokenFilter admin authority mapping")
class AdminSecurityIT {

    @Mock private UserLinkerService userLinkerService;
    @Mock private AdminAuthService adminAuthService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;
    @Mock private FirebaseAuth mockFirebaseAuth;
    @Mock private FirebaseToken mockToken;

    private static final String SUPPORT_UID = "uid-support-001";
    private static final String DISABLED_UID = "uid-disabled-001";
    private static final String NON_ADMIN_UID = "uid-user-001";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private FirebaseTokenFilter buildFilter() {
        return new FirebaseTokenFilter(userLinkerService, new ObjectMapper(), adminAuthService, TestMessages.resolver(), false, "", "test");
    }

    /**
     * Builds a SUPPORT AdminAuthorities with the standard SUPPORT permission set.
     */
    private AdminAuthorities supportAuthorities() {
        UUID adminId = UUID.randomUUID();
        Set<GrantedAuthority> authorities = AdminRole.SUPPORT.permissions().stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                .collect(Collectors.toSet());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new AdminAuthorities(AdminRole.SUPPORT, authorities, false, "support@yadony.app", adminId);
    }

    private void setupFirebaseMock(MockedStatic<FirebaseAuth> staticAuth,
                                   MockedStatic<FirebaseApp> staticApp,
                                   String uid) throws Exception {
        staticApp.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
        staticAuth.when(FirebaseAuth::getInstance).thenReturn(mockFirebaseAuth);
        when(mockToken.getUid()).thenReturn(uid);
        when(mockFirebaseAuth.verifyIdToken("fake-token")).thenReturn(mockToken);
    }

    // -------------------------------------------------------------------------
    // Test 1: SUPPORT token on /admin/test-user-view → SecurityContext has AdminPrincipal + USER_VIEW
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SUPPORT token → AdminPrincipal in SecurityContext with USER_VIEW authority")
    void supportToken_adminPrincipalWithUserViewAuthority() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(request.getContextPath()).thenReturn("/api/v1");
        when(request.getRequestURI()).thenReturn("/api/v1/admin/test-user-view");
        when(adminAuthService.resolve(SUPPORT_UID)).thenReturn(Optional.of(supportAuthorities()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            setupFirebaseMock(staticAuth, staticApp, SUPPORT_UID);
            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = (UsernamePasswordAuthenticationToken)
                SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(AdminPrincipal.class);

        AdminPrincipal principal = (AdminPrincipal) auth.getPrincipal();
        assertThat(principal.role()).isEqualTo(AdminRole.SUPPORT);
        assertThat(principal.email()).isEqualTo("support@yadony.app");
        assertThat(principal.mustChangePassword()).isFalse();

        Set<String> authorityNames = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertThat(authorityNames).contains(AdminPermission.USER_VIEW.name());
        assertThat(authorityNames).contains("ROLE_ADMIN");

        // Filter chain proceeds (admin resolved, not blocked)
        verify(filterChain).doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Test 2: DISABLED admin token on /admin/** → 403, filter chain NOT called
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DISABLED admin token on /admin/** → 403, filter chain not called")
    void disabledAdminToken_onAdminRoute_returns403() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        // URI stubbée avec le context path, comme en production (/api/v1/…) —
        // sans lui le test validait un garde-fou qui ne matchait jamais.
        when(request.getContextPath()).thenReturn("/api/v1");
        when(request.getRequestURI()).thenReturn("/api/v1/admin/test-user-view");
        // DISABLED resolves to empty
        when(adminAuthService.resolve(DISABLED_UID)).thenReturn(Optional.empty());
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            setupFirebaseMock(staticAuth, staticApp, DISABLED_UID);
            buildFilter().doFilterInternal(request, response, filterChain);
        }

        verify(response).setStatus(SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // -------------------------------------------------------------------------
    // Test 3: Non-admin token on /admin/** → 403, filter chain NOT called
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Non-admin token on /admin/** → 403, filter chain not called")
    void nonAdminToken_onAdminRoute_returns403() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        // URI stubbée avec le context path, comme en production (/api/v1/…) —
        // sans lui le test validait un garde-fou qui ne matchait jamais.
        when(request.getContextPath()).thenReturn("/api/v1");
        when(request.getRequestURI()).thenReturn("/api/v1/admin/test-user-view");
        // Not an admin at all
        when(adminAuthService.resolve(NON_ADMIN_UID)).thenReturn(Optional.empty());
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            setupFirebaseMock(staticAuth, staticApp, NON_ADMIN_UID);
            buildFilter().doFilterInternal(request, response, filterChain);
        }

        verify(response).setStatus(SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
    }

    // -------------------------------------------------------------------------
    // Test 4: SUPPORT token lacks PAYMENT_REFUND authority
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SUPPORT token does not have PAYMENT_REFUND authority")
    void supportToken_doesNotHavePaymentRefund() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(request.getContextPath()).thenReturn("/api/v1");
        when(request.getRequestURI()).thenReturn("/api/v1/admin/test-user-view");
        when(adminAuthService.resolve(SUPPORT_UID)).thenReturn(Optional.of(supportAuthorities()));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            setupFirebaseMock(staticAuth, staticApp, SUPPORT_UID);
            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = (UsernamePasswordAuthenticationToken)
                SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();

        Set<String> authorityNames = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        // SUPPORT does NOT have PAYMENT_REFUND
        assertThat(authorityNames).doesNotContain(AdminPermission.PAYMENT_REFUND.name());
        // But does have USER_VIEW and PAYMENT_VIEW
        assertThat(authorityNames).contains(AdminPermission.USER_VIEW.name());
        assertThat(authorityNames).contains(AdminPermission.PAYMENT_VIEW.name());

        // Filter chain proceeds (admin is authenticated)
        verify(filterChain).doFilter(request, response);
    }

    // -------------------------------------------------------------------------
    // Test 5: SUPPORT token on non-admin route → utilisateur normal (YADONY-BACK-PROD-B)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SUPPORT token sur une route de l'app → principal uid String + rôles utilisateur, aucun droit admin")
    void supportToken_onNonAdminRoute_isRegularUser() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer fake-token");
        when(request.getContextPath()).thenReturn("/api/v1");
        when(request.getRequestURI()).thenReturn("/api/v1/wallet/balance");
        when(adminAuthService.resolve(SUPPORT_UID)).thenReturn(Optional.of(supportAuthorities()));
        UserEntity user = new UserEntity();
        user.setFirebaseUid(SUPPORT_UID);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER)));
        when(userLinkerService.resolveAndLink(eq(SUPPORT_UID), any())).thenReturn(Optional.of(user));

        try (MockedStatic<FirebaseAuth> staticAuth = mockStatic(FirebaseAuth.class);
             MockedStatic<FirebaseApp> staticApp = mockStatic(FirebaseApp.class)) {
            setupFirebaseMock(staticAuth, staticApp, SUPPORT_UID);
            buildFilter().doFilterInternal(request, response, filterChain);
        }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo(SUPPORT_UID);
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SENDER");
        verify(request).setAttribute(AdminPrincipal.ADMIN_ACCOUNT_ATTRIBUTE, Boolean.TRUE);
        verify(filterChain).doFilter(request, response);
    }
}
