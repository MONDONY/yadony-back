package com.yadony.api.admin.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/**
 * Bootstrap endpoint for one-time SUPER_ADMIN creation.
 *
 * Task 8 — AdminBootstrapController
 *
 * Security:
 * - If one bootstrap setting is blank, the endpoint returns 404 (disabled)
 * - The secret is compared in constant time (MessageDigest.isEqual) to prevent timing attacks
 * - The endpoint is permit-all in SecurityConfig (no Firebase token required)
 */
@RestController
@RequestMapping("/admin/bootstrap")
public class AdminBootstrapController {

    /** Seule identité que l'amorçage accepte ({@code ADMIN_ROOT_EMAIL}) ; vide = amorçage désactivé. */
    @Value("${yadony.admin.root-email:}")
    private String rootEmail;

    @Value("${yadony.admin.bootstrap.secret:}")
    private String bootstrapSecret;

    @Value("${yadony.admin.bootstrap.email:}")
    private String bootstrapEmail;

    @Value("${yadony.admin.bootstrap.password:}")
    private String bootstrapPassword;

    private final AdminAccountService adminAccountService;
    private final AdminUserRepository adminUserRepository;

    public AdminBootstrapController(AdminAccountService adminAccountService,
                                    AdminUserRepository adminUserRepository) {
        this.adminAccountService = adminAccountService;
        this.adminUserRepository = adminUserRepository;
    }

    /**
     * Creates the root account once; existing SUPER_ADMIN accounts are never modified.
     *
     * @param providedSecret value of the {@code X-Bootstrap-Secret} header
     * @return 404 if bootstrap is disabled, 403 if secret is wrong,
     *         201 with the root email if super-admin was created,
     *         409 if a super-admin already exists
     */
    @PostMapping
    public ResponseEntity<?> bootstrap(
            @RequestHeader(value = "X-Bootstrap-Secret", required = false) String providedSecret) {

        if (!bootstrapConfigured()) {
            return ResponseEntity.notFound().build();
        }

        if (!constantTimeEquals(bootstrapSecret, providedSecret)) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.FORBIDDEN, "Invalid bootstrap secret");
            pd.setType(URI.create("https://yadony.app/errors/forbidden"));
            pd.setTitle("Forbidden");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
        }

        String normalizedBootstrapEmail = bootstrapEmail.trim().toLowerCase(Locale.ROOT);
        if (!rootEmail.trim().toLowerCase(Locale.ROOT).equals(normalizedBootstrapEmail)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (adminUserRepository.countByRole(AdminRole.SUPER_ADMIN) > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        adminAccountService.bootstrapSuperAdmin(normalizedBootstrapEmail, bootstrapPassword);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("email", normalizedBootstrapEmail));
    }

    private boolean bootstrapConfigured() {
        return rootEmail != null && !rootEmail.isBlank()
                && bootstrapSecret != null && !bootstrapSecret.isBlank()
                && bootstrapEmail != null && !bootstrapEmail.isBlank()
                && bootstrapPassword != null && !bootstrapPassword.isBlank();
    }

    /**
     * Compares two strings in constant time to prevent timing attacks.
     * Returns false if either argument is null.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
