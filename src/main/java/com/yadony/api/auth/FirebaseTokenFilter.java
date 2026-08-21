package com.yadony.api.auth;

import com.yadony.api.admin.account.AdminAuthService;
import com.yadony.api.admin.account.AdminAuthorities;
import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.FirebaseSignInProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class FirebaseTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final UUID DEV_ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UserLinkerService userLinkerService;
    private final ObjectMapper objectMapper;
    private final AdminAuthService adminAuthService;
    private final boolean devAuthBypassEnabled;
    /** Jeton de bypass — fourni via secret, aucune valeur par défaut. Vide = bypass inerte. */
    private final String devBypassToken;
    private final String activeProfile;

    public FirebaseTokenFilter(UserLinkerService userLinkerService,
                               ObjectMapper objectMapper,
                               AdminAuthService adminAuthService,
                               @Value("${yadony.dev.auth-bypass:false}") boolean devAuthBypassEnabled,
                               @Value("${yadony.dev.bypass-token:}") String devBypassToken,
                               @Value("${spring.profiles.active:}") String activeProfile) {
        this.userLinkerService = userLinkerService;
        this.objectMapper = objectMapper;
        this.adminAuthService = adminAuthService;
        this.devBypassToken = devBypassToken != null ? devBypassToken.trim() : "";
        this.activeProfile = activeProfile != null ? activeProfile.trim() : "";
        // Fail-closed : le bypass n'est réellement actif que hors prod ET avec un jeton non vide.
        // Un profil prod avec le flag activé refuse de démarrer plutôt que d'exposer un backdoor.
        if (devAuthBypassEnabled && isProdProfile(this.activeProfile)) {
            throw new IllegalStateException(
                    "yadony.dev.auth-bypass=true est interdit en profil prod — backdoor d'authentification");
        }
        this.devAuthBypassEnabled = devAuthBypassEnabled && !isProdProfile(this.activeProfile);
        if (this.devAuthBypassEnabled && this.devBypassToken.isEmpty()) {
            log.warn("yadony.dev.auth-bypass=true mais yadony.dev.bypass-token vide — bypass inerte (fail-closed)");
        }
    }

    private static boolean isProdProfile(String profile) {
        for (String p : profile.split(",")) {
            if ("prod".equalsIgnoreCase(p.trim())) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            boolean blocked = authenticateToken(token, request, response);
            if (blocked) return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * @return true if the request was blocked (admin-only route accessed by non-admin, or suspended/banned user),
     *         false to continue
     */
    private boolean authenticateToken(String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // Bypass dev : uniquement si activé (hors prod), jeton non vide correspondant,
        // ET requête provenant de la loopback — jamais exploitable à distance.
        if (devAuthBypassEnabled && !devBypassToken.isEmpty()
                && devBypassToken.equals(token) && isLoopback(request)) {
            injectDevSuperAdmin();
            return false;
        }

        if (!isFirebaseReady()) return false;

        FirebaseToken decoded;
        String uid;
        try {
            decoded = FirebaseAuth.getInstance().verifyIdToken(token);
            uid = decoded.getUid();
        } catch (FirebaseAuthException e) {
            log.debug("Invalid Firebase token: {}", e.getMessage());
            SecurityContextHolder.clearContext();
            return false;
        }

        // Admin fast-path: resolve admin before normal user lookup
        Optional<AdminAuthorities> adminOpt = adminAuthService.resolve(uid);
        if (adminOpt.isPresent()) {
            AdminAuthorities admin = adminOpt.get();
            if (admin.mustChangePassword() && !isPasswordChangeAllowed(request)) {
                writeForbidden(response, "PASSWORD_CHANGE_REQUIRED", "Password change required");
                return true;
            }
            UsernamePasswordAuthenticationToken adminAuth =
                    new UsernamePasswordAuthenticationToken(
                            new AdminPrincipal(admin.adminId(), admin.email(), admin.role(), admin.mustChangePassword(), uid),
                            decoded,
                            admin.authorities()
                    );
            SecurityContextHolder.getContext().setAuthentication(adminAuth);
            return false; // not blocked
        }
        // If request targets admin routes and caller is not an admin → 403.
        // getRequestURI() includes the servlet context path (/api/v1), which must be
        // stripped or this guard never matches in production.
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        if (request.getRequestURI().startsWith(contextPath + "/admin/")) {
            writeForbidden(response, "Accès réservé aux administrateurs");
            return true;
        }

        try {
            // Le statut invité vient du TOKEN, avant toute lecture de la base : un
            // anonyme n'a le plus souvent aucune ligne (matérialisation paresseuse),
            // et une ligne existante ne doit jamais le promouvoir.
            boolean isGuest = FirebaseSignInProvider.isAnonymous(decoded);

            UserEntity user = userLinkerService.resolveAndLink(uid, decoded).orElse(null);

            if (isGuest) {
                if (user != null
                        && (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.BANNED)) {
                    writeForbidden(response, "Votre compte est suspendu ou banni");
                    return true;
                }
                setAuthentication(uid, decoded, List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
                return false;
            }

            if (user == null) {
                // New user — not yet registered; allow with empty roles (registration flow)
                setAuthentication(uid, decoded, List.of());
                return false;
            }

            if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.BANNED) {
                writeForbidden(response, "Votre compte est suspendu ou banni");
                return true;
            }

            List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                    .toList();

            setAuthentication(uid, decoded, authorities);
        } catch (Exception e) {
            log.warn("Could not load user from DB for uid {}: {}", uid, e.getMessage());
            SecurityContextHolder.clearContext(); // do NOT grant access on DB failure
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service temporarily unavailable");
            return true;
        }

        return false;
    }

    /** Vrai si la requête provient de la boucle locale (127.0.0.0/8, ::1). */
    private static boolean isLoopback(HttpServletRequest request) {
        try {
            return java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private void injectDevSuperAdmin() {
        Set<GrantedAuthority> authorities = EnumSet.allOf(AdminPermission.class).stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));

        AdminPrincipal principal = new AdminPrincipal(DEV_ADMIN_ID, "dev-admin@yadony.invalid", AdminRole.SUPER_ADMIN, false, "dev-uid");
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void setAuthentication(String uid, FirebaseToken decoded,
                                   List<SimpleGrantedAuthority> authorities) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(uid, decoded, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * True only for the two routes a {@code mustChangePassword} admin may still call:
     * {@code GET /admin/me} and {@code POST /admin/me/change-password}. The URI includes
     * the servlet context path (/api/v1), which must be stripped before comparing.
     */
    private boolean isPasswordChangeAllowed(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String path = request.getRequestURI();
        if (path != null && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String method = request.getMethod();
        return ("GET".equals(method) && "/admin/me".equals(path))
                || ("POST".equals(method) && "/admin/me/change-password".equals(path));
    }

    private void writeForbidden(HttpServletResponse response, String detail) throws IOException {
        writeForbidden(response, null, detail);
    }

    private void writeForbidden(HttpServletResponse response, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        problem.setType(URI.create("https://yadony.app/errors/access-denied"));
        problem.setTitle("Access Denied");
        if (code != null) {
            problem.setProperty("code", code);
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private boolean isFirebaseReady() {
        try {
            return !FirebaseApp.getApps().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
