package com.yadony.api.admin.account;

import com.yadony.api.admin.account.dto.CreateAdminRequest;
import com.yadony.api.admin.account.dto.CredentialsResponse;
import com.yadony.api.admin.account.dto.UpdateAdminRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Service for admin account lifecycle: create, reset/change password, update, delete.
 *
 * Task 7 — AdminAccountService (Firebase + DB + guards)
 *
 * Guards enforced:
 * - Cannot disable/delete yourself (actorId == adminId)
 * - Cannot disable/delete the last active SUPER_ADMIN
 */
@Service
@Transactional(noRollbackFor = AdminAccountService.RefreshTokenRevocationPartialException.class)
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private static final String CHARS_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String CHARS_LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String CHARS_DIGIT = "0123456789";
    private static final String CHARS_SYMBOL = "!@#$%^&*()-_=+[]{}";
    private static final String CHARS_ALL = CHARS_UPPER + CHARS_LOWER + CHARS_DIGIT + CHARS_SYMBOL;
    private static final int PASSWORD_LENGTH = 20;
    private static final String ROOT_EMAIL = "aboubakar.diakite@yadony.com";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminUserRepository adminUserRepository;
    private final FirebaseAuth firebaseAuth;
    private final AuditService auditService;
    private final AdminAuthService adminAuthService;
    private final AdminSessionRevocationCheck sessionRevocationCheck;

    public AdminAccountService(AdminUserRepository adminUserRepository,
                                @Nullable FirebaseAuth firebaseAuth,
                                AuditService auditService,
                                AdminAuthService adminAuthService,
                                AdminSessionRevocationCheck sessionRevocationCheck) {
        this.adminUserRepository = adminUserRepository;
        this.firebaseAuth = firebaseAuth;
        this.auditService = auditService;
        this.adminAuthService = adminAuthService;
        this.sessionRevocationCheck = sessionRevocationCheck;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /** Creates a new admin account and returns its temporary password once. */
    public CredentialsResponse createAdmin(CreateAdminRequest req, UUID actorId) {
        if (req == null || !isManagedRole(req.role())) {
            throw business(HttpStatus.FORBIDDEN, "ADMIN_ROLE_FORBIDDEN", "Role forbidden");
        }

        String email = normalizeEmail(req.email());
        if (ROOT_EMAIL.equalsIgnoreCase(email)) {
            throw business(HttpStatus.FORBIDDEN, "ADMIN_ROLE_FORBIDDEN", "Role forbidden");
        }
        return createFirebaseAndPersist(email, generatePassword(), req.role(), actorId);
    }

    /** Creates the one permitted SUPER_ADMIN account during the protected bootstrap flow. */
    public void bootstrapSuperAdmin(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        if (!ROOT_EMAIL.equalsIgnoreCase(email) || password == null || password.isBlank()) {
            throw business(HttpStatus.FORBIDDEN, "ADMIN_ROLE_FORBIDDEN", "Role forbidden");
        }
        if (adminUserRepository.countByRole(AdminRole.SUPER_ADMIN) > 0) {
            throw business(HttpStatus.CONFLICT, "ADMIN_SUPER_ADMIN_IMMUTABLE", "SUPER_ADMIN is immutable");
        }
        createFirebaseAndPersist(email, password, AdminRole.SUPER_ADMIN, null);
    }

    private CredentialsResponse createFirebaseAndPersist(String email, String password, AdminRole role, UUID actorId) {
        if (adminUserRepository.existsByEmailIgnoreCase(email)) {
            throw business(HttpStatus.CONFLICT, "ADMIN_EMAIL_DUPLICATE", "Email already in use");
        }

        String firebaseUid;
        try {
            UserRecord.CreateRequest createRequest = new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setPassword(password)
                    .setDisplayName(email);
            UserRecord userRecord = requireFirebase().createUser(createRequest);
            firebaseUid = userRecord.getUid();
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                log.error("Firebase createUser: EMAIL_ALREADY_EXISTS for email={} (orphaned Firebase user? not in Postgres)", email);
                throw business(HttpStatus.CONFLICT, "ADMIN_EMAIL_DUPLICATE", "Email already in use");
            }
            log.error("Firebase createUser failed for email={}: {}", email, e.getAuthErrorCode(), e);
            throw business(HttpStatus.INTERNAL_SERVER_ERROR, "FIREBASE_CREATE_FAILED", "Firebase account creation failed");
        }

        try {
            requireFirebase().setCustomUserClaims(firebaseUid, Map.of("ROLE_ADMIN", true));
        } catch (FirebaseAuthException e) {
            log.error("Firebase setCustomUserClaims failed for uid={}: {}", firebaseUid, e.getAuthErrorCode(), e);
            rollbackFirebaseUser(firebaseUid);
            throw business(HttpStatus.INTERNAL_SERVER_ERROR, "FIREBASE_CREATE_FAILED", "Firebase account creation failed");
        }

        AdminUserEntity entity = new AdminUserEntity(firebaseUid, email, role);
        entity.setMustChangePassword(true);
        entity.setStatus(AdminStatus.ACTIVE);
        entity.setCreatedBy(actorId);
        try {
            adminUserRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            rollbackFirebaseUser(firebaseUid);
            throw business(HttpStatus.CONFLICT, "ADMIN_EMAIL_DUPLICATE", "Email already in use");
        } catch (Exception e) {
            rollbackFirebaseUser(firebaseUid);
            throw business(HttpStatus.INTERNAL_SERVER_ERROR, "ADMIN_CREATE_DB_FAILED", "Admin account creation failed");
        }
        adminAuthService.evictByFirebaseUid(firebaseUid);

        auditService.log(
                "admin_users",
                entity.getId(),
                "ADMIN_ACCOUNT_CREATED",
                actorId,
                Map.of("email", email, "role", role.name())
        );

        log.info("Admin account created: role={}, by={}", role, actorId);
        return new CredentialsResponse(email, password);
    }

    // -------------------------------------------------------------------------
    // Reset password (by another admin)
    // -------------------------------------------------------------------------

    /**
     * Resets the password of an existing admin account (called by a super admin).
     * Sets mustChangePassword=true so the account holder must change on next login.
     */
    public CredentialsResponse resetPassword(UUID adminId, UUID actorId) {
        AdminUserEntity entity = findOrThrow(adminId);
        rejectRootMutation(entity);
        String newPassword = generatePassword();

        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(entity.getFirebaseUid())
                    .setPassword(newPassword);
            requireFirebase().updateUser(updateRequest);
        } catch (FirebaseAuthException e) {
            log.error("Firebase updateUser (reset) failed for adminId={}: {}", adminId, e.getMessage());
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_UPDATE_FAILED",
                    "Firebase password reset failed",
                    e.getMessage()
            );
        }

        entity.setMustChangePassword(true);
        adminUserRepository.saveAndFlush(entity);

        adminAuthService.evictByFirebaseUid(entity.getFirebaseUid());

        auditService.log(
                "admin_users",
                adminId,
                "ADMIN_PASSWORD_RESET",
                actorId,
                Map.of("email", entity.getEmail())
        );

        revokeRefreshTokens(entity.getFirebaseUid());

        return new CredentialsResponse(entity.getEmail(), newPassword);
    }

    // -------------------------------------------------------------------------
    // Change own password
    // -------------------------------------------------------------------------

    /**
     * Allows an admin to change their own password.
     * Current password verification is handled by Firebase re-auth on the front end.
     * Back: just updates Firebase + sets mustChangePassword=false.
     */
    public void changeOwnPassword(UUID adminId, String newPassword, UUID actorId) {
        if (newPassword == null || newPassword.length() < 12) {
            throw business(HttpStatus.BAD_REQUEST, "ADMIN_PASSWORD_TOO_SHORT",
                    "Password must contain at least 12 characters");
        }
        AdminUserEntity entity = findOrThrow(adminId);

        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(entity.getFirebaseUid())
                    .setPassword(newPassword);
            requireFirebase().updateUser(updateRequest);
        } catch (FirebaseAuthException e) {
            log.error("Firebase updateUser (changeOwn) failed for adminId={}: {}", adminId, e.getMessage());
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_UPDATE_FAILED",
                    "Firebase password change failed",
                    e.getMessage()
            );
        }

        entity.setMustChangePassword(false);
        adminUserRepository.save(entity);

        adminAuthService.evictByFirebaseUid(entity.getFirebaseUid());

        auditService.log(
                "admin_users",
                adminId,
                "ADMIN_PASSWORD_CHANGED",
                actorId,
                Map.of("email", entity.getEmail())
        );
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    /**
     * Updates role, permission overrides, or status of an admin account.
     * Guards: cannot disable/delete the last active SUPER_ADMIN.
     *         Cannot disable yourself.
     */
    public AdminUserEntity updateAdmin(UUID adminId, UpdateAdminRequest req, UUID actorId) {
        AdminUserEntity entity = findOrThrow(adminId);
        rejectRootMutation(entity);

        // Guard: cannot disable yourself
        if (req.status() == AdminStatus.DISABLED && adminId.equals(actorId)) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT,
                    "ADMIN_SELF_DISABLE",
                    "Cannot disable your own account",
                    "You cannot disable your own admin account"
            );
        }

        if (req.role() != null) {
            if (!isManagedRole(req.role())) {
                throw business(HttpStatus.FORBIDDEN, "ADMIN_ROLE_FORBIDDEN", "Role forbidden");
            }
            entity.setRole(req.role());
        }
        if (req.permissionOverrides() != null) {
            entity.setPermissionOverrides(new HashMap<>(req.permissionOverrides()));
        }
        boolean refreshTokensMustBeRevoked = req.status() == AdminStatus.DISABLED;
        if (req.status() != null) {
            entity.setStatus(req.status());
            // Sync Firebase disabled state
            if (req.status() == AdminStatus.DISABLED) {
                disableFirebaseUser(entity.getFirebaseUid());
            } else if (req.status() == AdminStatus.ACTIVE) {
                enableFirebaseUser(entity.getFirebaseUid());
            }
        }
        String firebaseUidForEvict = entity.getFirebaseUid();
        adminUserRepository.saveAndFlush(entity);
        adminAuthService.evictByFirebaseUid(firebaseUidForEvict);

        auditService.log(
                "admin_users",
                adminId,
                "ADMIN_ACCOUNT_UPDATED",
                actorId,
                Map.of("email", entity.getEmail())
        );

        if (refreshTokensMustBeRevoked) {
            revokeRefreshTokens(firebaseUidForEvict);
        }

        return entity;
    }

    // -------------------------------------------------------------------------
    // Delete (soft-delete + Firebase disable)
    // -------------------------------------------------------------------------

    /**
     * Soft-deletes an admin account and disables the corresponding Firebase user.
     * Guards: cannot delete yourself; cannot delete the last active SUPER_ADMIN.
     */
    public void deleteAdmin(UUID adminId, UUID actorId) {
        AdminUserEntity entity = findOrThrow(adminId);
        rejectRootMutation(entity);

        // Guard: cannot delete yourself
        if (adminId.equals(actorId)) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT,
                    "ADMIN_SELF_DELETE",
                    "Cannot delete your own account",
                    "You cannot delete your own admin account"
            );
        }

        // Capture firebaseUid before soft-delete (after soft-delete @Where filter hides the entity)
        String firebaseUid = entity.getFirebaseUid();

        // Disable in Firebase before soft-deleting
        disableFirebaseUser(firebaseUid);

        // Soft-delete: use BaseEntity helper
        entity.softDelete();
        entity.setStatus(AdminStatus.DISABLED);
        adminUserRepository.saveAndFlush(entity);

        adminAuthService.evictByFirebaseUid(firebaseUid);

        auditService.log(
                "admin_users",
                adminId,
                "ADMIN_ACCOUNT_DELETED",
                actorId,
                Map.of("email", entity.getEmail())
        );

        revokeRefreshTokens(firebaseUid);

        log.info("Admin account soft-deleted: id={}, by={}", adminId, actorId);
    }

    // -------------------------------------------------------------------------
    // Public helpers (also used by bootstrap)
    // -------------------------------------------------------------------------

    /**
     * Generates a strong password of at least 20 characters:
     * at least one uppercase, one lowercase, one digit, one symbol.
     */
    public String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        // Guarantee at least one of each required character class
        sb.append(CHARS_UPPER.charAt(RANDOM.nextInt(CHARS_UPPER.length())));
        sb.append(CHARS_LOWER.charAt(RANDOM.nextInt(CHARS_LOWER.length())));
        sb.append(CHARS_DIGIT.charAt(RANDOM.nextInt(CHARS_DIGIT.length())));
        sb.append(CHARS_SYMBOL.charAt(RANDOM.nextInt(CHARS_SYMBOL.length())));

        for (int i = 4; i < PASSWORD_LENGTH; i++) {
            sb.append(CHARS_ALL.charAt(RANDOM.nextInt(CHARS_ALL.length())));
        }

        // Shuffle to avoid predictable positions
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private FirebaseAuth requireFirebase() {
        if (firebaseAuth == null) {
            throw new IllegalStateException("Firebase is not available in this environment");
        }
        return firebaseAuth;
    }

    private AdminUserEntity findOrThrow(UUID adminId) {
        return adminUserRepository.findById(adminId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "ADMIN_NOT_FOUND",
                        "Admin account not found",
                        "No admin account found with id: " + adminId
                ));
    }

    private String normalizeEmail(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw business(HttpStatus.BAD_REQUEST, "ADMIN_EMAIL_INVALID", "Invalid email");
        }
        return email;
    }

    private boolean isManagedRole(AdminRole role) {
        return role == AdminRole.ADMIN || role == AdminRole.SUPPORT;
    }

    private void rejectRootMutation(AdminUserEntity entity) {
        if (entity.getRole() == AdminRole.SUPER_ADMIN || ROOT_EMAIL.equalsIgnoreCase(entity.getEmail())) {
            throw business(HttpStatus.CONFLICT, "ADMIN_SUPER_ADMIN_IMMUTABLE", "SUPER_ADMIN is immutable");
        }
    }

    private void rollbackFirebaseUser(String firebaseUid) {
        try {
            requireFirebase().deleteUser(firebaseUid);
        } catch (Exception e) {
            log.error("Firebase rollback failed for uid={}", firebaseUid);
        }
    }

    private void revokeRefreshTokens(String firebaseUid) {
        try {
            requireFirebase().revokeRefreshTokens(firebaseUid);
            // Les jetons déjà vérifiés ne doivent pas survivre 60 s de plus à la révocation.
            sessionRevocationCheck.forgetVerifiedSessions();
        } catch (FirebaseAuthException e) {
            log.error("Firebase refresh-token revocation failed for uid={}", firebaseUid);
            throw new RefreshTokenRevocationPartialException();
        }
    }

    static final class RefreshTokenRevocationPartialException extends YadonyBusinessException {
        private RefreshTokenRevocationPartialException() {
            super(HttpStatus.BAD_GATEWAY,
                    "ADMIN_REFRESH_TOKENS_REVOCATION_FAILED",
                    "Admin account updated with partial failure",
                    "Admin security state was persisted but refresh-token revocation failed");
        }
    }

    private YadonyBusinessException business(HttpStatus status, String errorCode, String title) {
        return new YadonyBusinessException(status, errorCode, title, title);
    }

    private void disableFirebaseUser(String firebaseUid) {
        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(firebaseUid)
                    .setDisabled(true);
            requireFirebase().updateUser(updateRequest);
        } catch (FirebaseAuthException e) {
            log.error("Firebase disable user failed for uid={}: {}", firebaseUid, e.getMessage());
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_UPDATE_FAILED",
                    "Firebase disable user failed",
                    e.getMessage()
            );
        }
    }

    private void enableFirebaseUser(String firebaseUid) {
        try {
            UserRecord.UpdateRequest updateRequest = new UserRecord.UpdateRequest(firebaseUid)
                    .setDisabled(false);
            requireFirebase().updateUser(updateRequest);
        } catch (FirebaseAuthException e) {
            log.error("Firebase enable user failed for uid={}: {}", firebaseUid, e.getMessage());
            throw new YadonyBusinessException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "FIREBASE_UPDATE_FAILED",
                    "Firebase enable user failed",
                    e.getMessage()
            );
        }
    }
}
