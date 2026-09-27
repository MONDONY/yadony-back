package com.yadony.api.e2e.config;

import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.auth.FirebaseTokenFilter;
import com.yadony.api.auth.UserLinkerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * E2E-only replacement for FirebaseTokenFilter. Reads X-Test-UID / X-Test-Roles
 * headers and populates the SecurityContext directly. Registered only via
 * E2EMockConfig @Bean so it runs inside Spring Security's filter chain (not as a
 * servlet filter), avoiding the OncePerRequestFilter double-registration pitfall.
 *
 * <p>The credentials carry a stub {@link FirebaseToken} whose
 * {@code firebase.sign_in_provider} claim defaults to {@code "phone"} (overridable
 * per-request via the {@code X-Test-Provider} header). {@code AuthController.register}
 * reads it to pick the registration branch — without it, {@code createUser} hits the
 * {@code default} switch case and rejects every registration with 422
 * {@code invalid-provider}, leaving no user in the DB (→ 404 on every later call).
 */
public class TestFirebaseTokenFilter extends FirebaseTokenFilter {

    public TestFirebaseTokenFilter(UserLinkerService userLinkerService,
                                   ObjectMapper objectMapper,
                                   AdminAuthService adminAuthService) {
        super(userLinkerService, objectMapper, adminAuthService, false, "", "e2e");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String testUid = request.getHeader("X-Test-UID");
        String testRoles = request.getHeader("X-Test-Roles");

        if (testUid != null && !testUid.isBlank()) {
            String provider = request.getHeader("X-Test-Provider");
            FirebaseToken token = stubToken(provider != null && !provider.isBlank() ? provider.trim() : "phone",
                    request.getHeader("X-Test-Email"),
                    request.getHeader("X-Test-Phone"),
                    testUid.trim());
            List<SimpleGrantedAuthority> authorities = parseRoles(testRoles);
            // X-Test-Admin-Id : compte back-office (admin_users). Le principal devient alors un
            // AdminPrincipal, comme en production, pour les endpoints qui journalisent l'admin.
            String testAdminId = request.getHeader("X-Test-Admin-Id");
            Object principal = testAdminId != null && !testAdminId.isBlank()
                    ? new com.yadony.api.admin.account.AdminPrincipal(
                            java.util.UUID.fromString(testAdminId.trim()), null,
                            com.yadony.api.admin.account.AdminRole.ADMIN, false, testUid.trim())
                    : testUid.trim();
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, token, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }

    /**
     * Builds a minimal FirebaseToken stub carrying the sign-in provider, and — for the
     * phone provider — the {@code phone_number} claim, désormais seule source du numéro
     * (il n'est plus stocké en base). Sans en-tête {@code X-Test-Phone}, un numéro
     * déterministe est dérivé de l'UID : un compte Firebase = un numéro.
     */
    private FirebaseToken stubToken(String provider, String email, String phone, String uid) {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("firebase", Map.of("sign_in_provider", provider));
        if ("phone".equals(provider)) {
            claims.put("phone_number",
                    phone != null && !phone.isBlank() ? phone.trim() : derivePhone(uid));
        }
        Mockito.when(token.getClaims()).thenReturn(claims);
        Mockito.when(token.getEmail()).thenReturn(email);
        return token;
    }

    /** Numéro stable pour un UID donné, au format E.164. */
    private static String derivePhone(String uid) {
        return String.format("+336%08d", Math.abs(uid.hashCode()) % 100_000_000);
    }

    private List<SimpleGrantedAuthority> parseRoles(String testRoles) {
        if (testRoles == null || testRoles.isBlank()) return List.of();
        return Arrays.stream(testRoles.split(","))
                .map(String::trim)
                .filter(r -> !r.isBlank())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
