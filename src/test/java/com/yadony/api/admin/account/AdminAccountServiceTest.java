package com.yadony.api.admin.account;

import com.yadony.api.admin.account.dto.CreateAdminRequest;
import com.yadony.api.admin.account.dto.CredentialsResponse;
import com.yadony.api.admin.account.dto.UpdateAdminRequest;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AdminAccountService — Task 7 (TDD).
 *
 * All Firebase and DB dependencies are mocked.
 * Tests cover: create (email / duplicate email), resetPassword, changeOwnPassword,
 * deleteAdmin (self-guard / last-SA guard / happy path), updateAdmin.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAccountService — account lifecycle")
class AdminAccountServiceTest {

    @Mock
    private AdminUserRepository adminUserRepository;

    @Mock
    private FirebaseAuth firebaseAuth;

    @Mock
    private AuditService auditService;

    @Mock
    private AdminAuthService adminAuthService;

    @Mock
    private AdminSessionRevocationCheck sessionRevocationCheck;

    @InjectMocks
    private AdminAccountService adminAccountService;

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    private AdminUserEntity buildEntity(UUID id, String firebaseUid, String email,
                                        AdminRole role, AdminStatus status) {
        AdminUserEntity entity = new AdminUserEntity(firebaseUid, email, role);
        entity.setStatus(status);
        entity.setMustChangePassword(false);
        // Reflectively set id since BaseEntity uses @GeneratedValue
        try {
            var field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set id on entity", e);
        }
        return entity;
    }

    private UserRecord mockUserRecord(String uid) {
        UserRecord userRecord = mock(UserRecord.class);
        when(userRecord.getUid()).thenReturn(uid);
        return userRecord;
    }

    // -------------------------------------------------------------------------
    // 1. createAdmin(email)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin(email) returns the email and a temporary password")
    void createAdmin_email_happyPath() throws Exception {
        // Arrange
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest("admin@yadony.test", AdminRole.ADMIN);

        when(adminUserRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        UserRecord userRecord = mockUserRecord("firebase-uid-new");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);

        // Act
        CredentialsResponse response = adminAccountService.createAdmin(req, actorId);

        // Assert
        assertThat(response.email()).isNotNull().isNotBlank();
        assertThat(response.temporaryPassword()).isNotNull().hasSizeGreaterThanOrEqualTo(16);

        verify(firebaseAuth).createUser(any(UserRecord.CreateRequest.class));
        verify(adminUserRepository).saveAndFlush(any(AdminUserEntity.class));
        verify(adminAuthService).evictByFirebaseUid("firebase-uid-new");
        verify(auditService).log(
                eq("admin_users"),
                any(),
                eq("ADMIN_ACCOUNT_CREATED"),
                eq(actorId),
                any()
        );
    }

    @Test
    @DisplayName("createAdmin(email) persists mustChangePassword=true")
    void createAdmin_email_mustChangePassword() throws Exception {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest("support@yadony.test", AdminRole.SUPPORT);

        when(adminUserRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        UserRecord userRecord = mockUserRecord("uid-abc");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);

        ArgumentCaptor<AdminUserEntity> entityCaptor = ArgumentCaptor.forClass(AdminUserEntity.class);

        adminAccountService.createAdmin(req, actorId);

        verify(adminUserRepository).saveAndFlush(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getMustChangePassword()).isTrue();
        assertThat(entityCaptor.getValue().getCreatedBy()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("createAdmin rejects SUPER_ADMIN roles from the panel")
    void createAdmin_rejectsSuperAdminRole() throws Exception {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> adminAccountService.createAdmin(
                new CreateAdminRequest("other@yadony.test", AdminRole.SUPER_ADMIN), actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_ROLE_FORBIDDEN");

        verify(firebaseAuth, never()).createUser(any());
        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAdmin normalizes a valid email and generates exactly 20 complex characters")
    void createAdmin_normalizesEmailAndGeneratesComplexPassword() throws Exception {
        UUID actorId = UUID.randomUUID();
        when(adminUserRepository.existsByEmailIgnoreCase("new.admin@yadony.test")).thenReturn(false);
        UserRecord userRecord = mockUserRecord("uid-new");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);
        CredentialsResponse response = adminAccountService.createAdmin(
                new CreateAdminRequest(" New.Admin@Yadony.test ", AdminRole.ADMIN), actorId);

        assertThat(response.email()).isEqualTo("new.admin@yadony.test");
        assertThat(response.temporaryPassword()).hasSize(20)
                .matches(".*[A-Z].*")
                .matches(".*[a-z].*")
                .matches(".*[0-9].*")
                .matches(".*[!@#$%^&*()\\-_=+\\[\\]{}].*");
        verify(adminUserRepository).saveAndFlush(argThat(entity ->
                entity.getEmail().equals("new.admin@yadony.test")
                        && Boolean.TRUE.equals(entity.getMustChangePassword())));
    }

    @Test
    @DisplayName("createAdmin rejects malformed emails")
    void createAdmin_rejectsMalformedEmail() throws Exception {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> adminAccountService.createAdmin(
                new CreateAdminRequest("not-an-email", AdminRole.ADMIN), actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_EMAIL_INVALID");

        verify(firebaseAuth, never()).createUser(any());
    }

    @Test
    @DisplayName("createAdmin refuses the canonical root email from the panel")
    void createAdmin_rejectsCanonicalRootEmail() throws Exception {
        UUID actorId = UUID.randomUUID();

        assertThatThrownBy(() -> adminAccountService.createAdmin(
                new CreateAdminRequest("aboubakar.diakite@yadony.com", AdminRole.ADMIN), actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_ROLE_FORBIDDEN");

        verify(firebaseAuth, never()).createUser(any());
    }

    @Test
    @DisplayName("bootstrapSuperAdmin creates the canonical root as the only SUPER_ADMIN")
    void bootstrapSuperAdmin_createsCanonicalRoot() throws Exception {
        when(adminUserRepository.countByRole(AdminRole.SUPER_ADMIN)).thenReturn(0L);
        when(adminUserRepository.existsByEmailIgnoreCase("aboubakar.diakite@yadony.com")).thenReturn(false);
        UserRecord userRecord = mockUserRecord("uid-bootstrap-root");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);
        adminAccountService.bootstrapSuperAdmin(" Aboubakar.Diakite@Yadony.com ", "test-only-value");

        verify(adminUserRepository).saveAndFlush(argThat(entity ->
                entity.getRole() == AdminRole.SUPER_ADMIN
                        && entity.getEmail().equals("aboubakar.diakite@yadony.com")
                        && Boolean.TRUE.equals(entity.getMustChangePassword())));
    }

    // -------------------------------------------------------------------------
    // 2. createAdmin → setCustomUserClaims fails → deleteUser called + exception propagated
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin rolls back Firebase when claim assignment fails")
    void createAdmin_claimsFail_rollbackAndPropagate() throws Exception {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest("admin@yadony.test", AdminRole.ADMIN);

        when(adminUserRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        UserRecord userRecord = mockUserRecord("firebase-uid-claims-fail");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);
        FirebaseAuthException claimsException = mock(FirebaseAuthException.class);
        doThrow(claimsException).when(firebaseAuth).setCustomUserClaims(eq("firebase-uid-claims-fail"), any());

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_CREATE_FAILED");

        // Firebase user must be rolled back
        verify(firebaseAuth).deleteUser("firebase-uid-claims-fail");
        // Entity must NOT be persisted
        verify(adminUserRepository, never()).save(any());
        // No audit entry
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("createAdmin refuses an existing Firebase identity without changing it")
    void createAdmin_firebaseEmailCollision_refusesWithoutRecovery() throws Exception {
        UUID actorId = UUID.randomUUID();
        FirebaseAuthException collision = mock(FirebaseAuthException.class);
        when(collision.getAuthErrorCode()).thenReturn(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        when(adminUserRepository.existsByEmailIgnoreCase("existing@yadony.test")).thenReturn(false);
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenThrow(collision);

        assertThatThrownBy(() -> adminAccountService.createAdmin(
                new CreateAdminRequest("existing@yadony.test", AdminRole.SUPPORT), actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_EMAIL_DUPLICATE");

        verify(firebaseAuth, never()).getUserByEmail(anyString());
        verify(firebaseAuth, never()).updateUser(any(UserRecord.UpdateRequest.class));
        verify(firebaseAuth, never()).deleteUser(anyString());
        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAdmin rolls back its Firebase user when saveAndFlush detects an email collision")
    void createAdmin_databaseFlushCollision_rollsBackFirebaseUser() throws Exception {
        UUID actorId = UUID.randomUUID();
        when(adminUserRepository.existsByEmailIgnoreCase("race@yadony.test")).thenReturn(false);
        UserRecord userRecord = mockUserRecord("uid-race");
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(userRecord);
        when(adminUserRepository.saveAndFlush(any(AdminUserEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique email"));

        assertThatThrownBy(() -> adminAccountService.createAdmin(
                new CreateAdminRequest("race@yadony.test", AdminRole.ADMIN), actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_EMAIL_DUPLICATE");

        verify(firebaseAuth).deleteUser("uid-race");
        verify(adminUserRepository).saveAndFlush(any(AdminUserEntity.class));
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // 3. createAdmin(email duplicate) → exception
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin with a duplicate email throws a conflict")
    void createAdmin_duplicateEmail_throwsConflict() throws Exception {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest("dup@yadony.test", AdminRole.ADMIN);

        when(adminUserRepository.existsByEmailIgnoreCase("dup@yadony.test")).thenReturn(true);

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_EMAIL_DUPLICATE");

        verify(firebaseAuth, never()).createUser(any());
        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 3. resetPassword
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resetPassword → new password generated, FirebaseAuth.updateUser called, mustChangePassword=true, audit ADMIN_PASSWORD_RESET")
    void resetPassword_happyPath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-reset", "admin.1@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        CredentialsResponse response = adminAccountService.resetPassword(adminId, actorId);

        assertThat(response.temporaryPassword()).isNotNull().hasSizeGreaterThanOrEqualTo(16);
        assertThat(response.email()).isEqualTo("admin.1@yadony.test");

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        assertThat(entity.getMustChangePassword()).isTrue();

        verify(auditService).log(
                eq("admin_users"),
                eq(adminId),
                eq("ADMIN_PASSWORD_RESET"),
                eq(actorId),
                any()
        );
        verify(adminAuthService).evictByFirebaseUid("uid-reset");
        verify(firebaseAuth).revokeRefreshTokens("uid-reset");
        // Audit sécu 21/10 : un jeton vérifié il y a moins de 60 s ne survit pas à la révocation.
        verify(sessionRevocationCheck).forgetVerifiedSessions();
        verify(adminUserRepository).saveAndFlush(entity);
    }

    @Test
    @DisplayName("resetPassword rejects every mutation of a root account")
    void resetPassword_rootAccount_throwsImmutable() throws Exception {
        UUID adminId = UUID.randomUUID();
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(
                buildEntity(adminId, "uid-root", "aboubakar.diakite@yadony.com", AdminRole.ADMIN, AdminStatus.ACTIVE)));

        assertThatThrownBy(() -> adminAccountService.resetPassword(adminId, UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_SUPER_ADMIN_IMMUTABLE");

        verify(firebaseAuth, never()).updateUser(any(UserRecord.UpdateRequest.class));
    }

    @Test
    @DisplayName("resetPassword persists the security state and evicts the cache when refresh-token revocation fails")
    void resetPassword_revokeFailure_persistsSecurityStateBeforePartialFailure() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AdminUserEntity entity = buildEntity(adminId, "uid-reset-revoke", "admin.reset@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        FirebaseAuthException revokeFailure = mock(FirebaseAuthException.class);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        doThrow(revokeFailure).when(firebaseAuth).revokeRefreshTokens("uid-reset-revoke");

        assertThatThrownBy(() -> adminAccountService.resetPassword(adminId, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_REFRESH_TOKENS_REVOCATION_FAILED");

        assertThat(entity.getMustChangePassword()).isTrue();
        verify(adminUserRepository).saveAndFlush(entity);
        verify(adminAuthService).evictByFirebaseUid("uid-reset-revoke");
        verify(auditService).log(eq("admin_users"), eq(adminId), eq("ADMIN_PASSWORD_RESET"), eq(actorId), any());
    }

    // -------------------------------------------------------------------------
    // 4. changeOwnPassword
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("changeOwnPassword → FirebaseAuth.updateUser called, mustChangePassword=false, audit ADMIN_PASSWORD_CHANGED")
    void changeOwnPassword_happyPath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = adminId; // same — it's own password change

        AdminUserEntity entity = buildEntity(adminId, "uid-own", "admin.2@yadony.test", AdminRole.SUPPORT, AdminStatus.ACTIVE);
        entity.setMustChangePassword(true);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        when(adminUserRepository.save(any())).thenReturn(entity);

        adminAccountService.changeOwnPassword(adminId, "NewStr0ng!P@ssword99", actorId);

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        assertThat(entity.getMustChangePassword()).isFalse();

        verify(auditService).log(
                eq("admin_users"),
                eq(adminId),
                eq("ADMIN_PASSWORD_CHANGED"),
                eq(actorId),
                any()
        );
        verify(adminAuthService).evictByFirebaseUid("uid-own");
    }

    @Test
    @DisplayName("changeOwnPassword → mot de passe < 12 caracteres → ADMIN_PASSWORD_TOO_SHORT, aucun appel Firebase")
    void changeOwnPassword_rejectsShortPassword() {
        UUID adminId = UUID.randomUUID();

        assertThatThrownBy(() -> adminAccountService.changeOwnPassword(adminId, "short1", adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("ADMIN_PASSWORD_TOO_SHORT"));

        verifyNoInteractions(firebaseAuth);
        verifyNoInteractions(adminUserRepository);
    }

    @Test
    @DisplayName("changeOwnPassword → SUPER_ADMIN racine peut changer son propre mot de passe")
    void rootCanChangeOwnPassword() throws Exception {
        UUID rootId = UUID.randomUUID();
        AdminUserEntity root = buildEntity(rootId, "uid-root", "aboubakar.diakite@yadony.com",
                AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
        root.setMustChangePassword(true);
        when(adminUserRepository.findById(rootId)).thenReturn(Optional.of(root));
        when(adminUserRepository.save(any())).thenReturn(root);

        adminAccountService.changeOwnPassword(rootId, "NewSecurePass123!", rootId);

        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
        assertThat(root.getMustChangePassword()).isFalse();
    }

    // -------------------------------------------------------------------------
    // 5. deleteAdmin(adminId, actorId=adminId) → guard: cannot delete yourself
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAdmin where adminId == actorId → YadonyBusinessException (self-delete guard)")
    void deleteAdmin_selfDelete_throws() throws Exception {
        UUID adminId = UUID.randomUUID();
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(
                buildEntity(adminId, "uid-self-delete", "admin.self@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE)));

        assertThatThrownBy(() -> adminAccountService.deleteAdmin(adminId, adminId))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("own");

        verify(adminUserRepository, never()).save(any());
        verify(firebaseAuth, never()).updateUser(any(UserRecord.UpdateRequest.class));
    }

    // -------------------------------------------------------------------------
    // 6. deleteAdmin → last SUPER_ADMIN active → guard throws
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAdmin on last active SUPER_ADMIN → YadonyBusinessException (last SA guard)")
    void deleteAdmin_lastSuperAdmin_throws() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-sa", "admin.super@yadony.test", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> adminAccountService.deleteAdmin(adminId, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("SUPER_ADMIN");

        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("deleteAdmin rejects every mutation of a root account")
    void deleteAdmin_rootAccount_throwsImmutable() throws Exception {
        UUID adminId = UUID.randomUUID();
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(
                buildEntity(adminId, "uid-root-delete", "root.other@yadony.test", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE)));

        assertThatThrownBy(() -> adminAccountService.deleteAdmin(adminId, UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_SUPER_ADMIN_IMMUTABLE");

        verify(firebaseAuth, never()).updateUser(any(UserRecord.UpdateRequest.class));
    }

    // -------------------------------------------------------------------------
    // 7. deleteAdmin → ordinary account → happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAdmin on an ordinary account → soft-delete, Firebase disabled, audit ADMIN_ACCOUNT_DELETED")
    void deleteAdmin_ordinaryAccount_happyPath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-del", "admin.del@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        adminAccountService.deleteAdmin(adminId, actorId);

        // Soft-delete: deletedAt should be set
        assertThat(entity.getDeletedAt()).isNotNull();
        assertThat(entity.getStatus()).isEqualTo(AdminStatus.DISABLED);

        // Firebase disabled
        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));

        // Audit
        verify(auditService).log(
                eq("admin_users"),
                eq(adminId),
                eq("ADMIN_ACCOUNT_DELETED"),
                eq(actorId),
                any()
        );

        // Cache evicted by firebaseUid (not adminId, to survive soft-delete @Where filter)
        verify(adminAuthService).evictByFirebaseUid("uid-del");
        verify(adminUserRepository).saveAndFlush(entity);
    }

    @Test
    @DisplayName("deleteAdmin persists the disabled soft-delete and evicts the cache when refresh-token revocation fails")
    void deleteAdmin_revokeFailure_persistsSecurityStateBeforePartialFailure() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AdminUserEntity entity = buildEntity(adminId, "uid-delete-revoke", "admin.delete@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        FirebaseAuthException revokeFailure = mock(FirebaseAuthException.class);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        doThrow(revokeFailure).when(firebaseAuth).revokeRefreshTokens("uid-delete-revoke");

        assertThatThrownBy(() -> adminAccountService.deleteAdmin(adminId, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_REFRESH_TOKENS_REVOCATION_FAILED");

        assertThat(entity.getDeletedAt()).isNotNull();
        assertThat(entity.getStatus()).isEqualTo(AdminStatus.DISABLED);
        verify(adminUserRepository).saveAndFlush(entity);
        verify(adminAuthService).evictByFirebaseUid("uid-delete-revoke");
        verify(auditService).log(eq("admin_users"), eq(adminId), eq("ADMIN_ACCOUNT_DELETED"), eq(actorId), any());
    }

    // -------------------------------------------------------------------------
    // Helper methods: generatePassword
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("generatePassword → length ≥ 16, contains upper/lower/digit/symbol")
    void generatePassword_strongPassword() {
        String password = adminAccountService.generatePassword();

        assertThat(password).hasSizeGreaterThanOrEqualTo(16);
        assertThat(password).matches(".*[A-Z].*");
        assertThat(password).matches(".*[a-z].*");
        assertThat(password).matches(".*[0-9].*");
        assertThat(password).matches(".*[!@#$%^&*()\\-_=+\\[\\]{}].*");
    }

    // -------------------------------------------------------------------------
    // Edge case: deleteAdmin on ADMIN role (not SUPER_ADMIN) → no last-SA check
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAdmin on ADMIN role → no last-SA guard needed, proceeds to soft-delete")
    void deleteAdmin_adminRole_noLastSaCheck() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-admin-del", "admin.3@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        adminAccountService.deleteAdmin(adminId, actorId);

        verify(adminUserRepository, never()).countByRoleAndStatus(any(), any());
        assertThat(entity.getDeletedAt()).isNotNull();
        verify(adminAuthService).evictByFirebaseUid("uid-admin-del");
        verify(firebaseAuth).revokeRefreshTokens("uid-admin-del");
    }

    // -------------------------------------------------------------------------
    // createAdmin(email=null) → ADMIN_EMAIL_INVALID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin(email=null) throws ADMIN_EMAIL_INVALID")
    void createAdmin_emailNull_throwsEmailRequired() {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest(null, AdminRole.ADMIN);

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_EMAIL_INVALID");

        verify(adminUserRepository, never()).existsByEmailIgnoreCase(any());
        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("createAdmin(email=blank) throws ADMIN_EMAIL_INVALID")
    void createAdmin_emailBlank_throwsEmailRequired() {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest("  ", AdminRole.ADMIN);

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_EMAIL_INVALID");
    }

    // -------------------------------------------------------------------------
    // createAdmin → Firebase createUser throws FirebaseAuthException → FIREBASE_CREATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdmin → Firebase createUser throws FirebaseAuthException → YadonyBusinessException FIREBASE_CREATE_FAILED")
    void createAdmin_firebaseCreateUser_throws_firebaseCreateFailed() throws Exception {
        UUID actorId = UUID.randomUUID();
        CreateAdminRequest req = new CreateAdminRequest("admin@yadony.test", AdminRole.ADMIN);

        when(adminUserRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(firebaseAuth.createUser(any(UserRecord.CreateRequest.class))).thenThrow(ex);

        assertThatThrownBy(() -> adminAccountService.createAdmin(req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_CREATE_FAILED");

        verify(adminUserRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // resetPassword → Firebase updateUser throws FirebaseAuthException → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resetPassword → Firebase updateUser throws FirebaseAuthException → YadonyBusinessException FIREBASE_UPDATE_FAILED")
    void resetPassword_firebaseUpdateUser_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-reset-fail", "admin.reset@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase update error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        assertThatThrownBy(() -> adminAccountService.resetPassword(adminId, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // changeOwnPassword → Firebase updateUser throws FirebaseAuthException → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("changeOwnPassword → Firebase updateUser throws FirebaseAuthException → YadonyBusinessException FIREBASE_UPDATE_FAILED")
    void changeOwnPassword_firebaseUpdateUser_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = adminId;

        AdminUserEntity entity = buildEntity(adminId, "uid-own-fail", "admin.own@yadony.test", AdminRole.SUPPORT, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase change error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        assertThatThrownBy(() -> adminAccountService.changeOwnPassword(adminId, "NewStr0ng!P@ssword99", actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=DISABLED, actorId == adminId → ADMIN_SELF_DISABLE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=DISABLED and actorId==adminId → YadonyBusinessException ADMIN_SELF_DISABLE")
    void updateAdmin_selfDisable_throws() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = adminId;

        AdminUserEntity entity = buildEntity(adminId, "uid-self-disable", "admin.self@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.DISABLED);

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_SELF_DISABLE");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=DISABLED on SUPER_ADMIN → ADMIN_SUPER_ADMIN_IMMUTABLE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=DISABLED on SUPER_ADMIN → YadonyBusinessException ADMIN_SUPER_ADMIN_IMMUTABLE")
    void updateAdmin_superAdmin_throwsImmutable() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-last-sa", "admin.superadmin@yadony.test", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.DISABLED);

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_SUPER_ADMIN_IMMUTABLE");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=ACTIVE → enableFirebaseUser called
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=ACTIVE → enableFirebaseUser called (Firebase updateUser with disabled=false)")
    void updateAdmin_statusActive_enableFirebaseUser() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-enable", "admin.disabled@yadony.test", AdminRole.ADMIN, AdminStatus.DISABLED);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.ACTIVE);

        adminAccountService.updateAdmin(adminId, req, actorId);

        ArgumentCaptor<UserRecord.UpdateRequest> captor = ArgumentCaptor.forClass(UserRecord.UpdateRequest.class);
        verify(firebaseAuth).updateUser(captor.capture());
        assertThat(entity.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        verify(adminAuthService).evictByFirebaseUid("uid-enable");
        verify(adminUserRepository).saveAndFlush(entity);
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=ACTIVE → Firebase enableUser throws → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=ACTIVE, Firebase enable throws → YadonyBusinessException FIREBASE_UPDATE_FAILED")
    void updateAdmin_statusActive_firebaseEnable_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-enable-fail", "admin.toenable@yadony.test", AdminRole.ADMIN, AdminStatus.DISABLED);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase enable error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.ACTIVE);

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → status=DISABLED → disableFirebaseUser throws → FIREBASE_UPDATE_FAILED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin with status=DISABLED, Firebase disable throws → YadonyBusinessException FIREBASE_UPDATE_FAILED")
    void updateAdmin_statusDisabled_firebaseDisable_throws_firebaseUpdateFailed() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-disable-fail", "admin.todisable@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        FirebaseAuthException ex = mock(FirebaseAuthException.class);
        when(ex.getMessage()).thenReturn("firebase disable error");
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(ex);

        UpdateAdminRequest req = new UpdateAdminRequest(null, null, AdminStatus.DISABLED);

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId, req, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("FIREBASE_UPDATE_FAILED");

        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateAdmin rejects every mutation of a root account")
    void updateAdmin_rootAccount_throwsImmutable() {
        UUID adminId = UUID.randomUUID();
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(
                buildEntity(adminId, "uid-root-update", "root.other@yadony.test", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE)));

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId,
                new UpdateAdminRequest(AdminRole.ADMIN, null, null), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_SUPER_ADMIN_IMMUTABLE");

        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateAdmin refuses promotions to SUPER_ADMIN")
    void updateAdmin_rejectsSuperAdminRole() {
        UUID adminId = UUID.randomUUID();
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(
                buildEntity(adminId, "uid-update-role", "admin@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE)));

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId,
                new UpdateAdminRequest(AdminRole.SUPER_ADMIN, null, null), UUID.randomUUID()))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_ROLE_FORBIDDEN");

        verify(adminUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateAdmin revokes refresh tokens after disabling an ordinary account")
    void updateAdmin_statusDisabled_revokesRefreshTokens() throws Exception {
        UUID adminId = UUID.randomUUID();
        AdminUserEntity entity = buildEntity(adminId, "uid-disable", "admin@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));

        adminAccountService.updateAdmin(adminId,
                new UpdateAdminRequest(null, null, AdminStatus.DISABLED), UUID.randomUUID());

        assertThat(entity.getStatus()).isEqualTo(AdminStatus.DISABLED);
        verify(firebaseAuth).revokeRefreshTokens("uid-disable");
        verify(adminAuthService).evictByFirebaseUid("uid-disable");
        verify(adminUserRepository).saveAndFlush(entity);
    }

    @Test
    @DisplayName("updateAdmin persists a disabled account and evicts the cache when refresh-token revocation fails")
    void updateAdmin_statusDisabled_revokeFailure_persistsSecurityStateBeforePartialFailure() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        AdminUserEntity entity = buildEntity(adminId, "uid-disable-revoke", "admin.disable@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        FirebaseAuthException revokeFailure = mock(FirebaseAuthException.class);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        doThrow(revokeFailure).when(firebaseAuth).revokeRefreshTokens("uid-disable-revoke");

        assertThatThrownBy(() -> adminAccountService.updateAdmin(adminId,
                new UpdateAdminRequest(null, null, AdminStatus.DISABLED), actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_REFRESH_TOKENS_REVOCATION_FAILED");

        assertThat(entity.getStatus()).isEqualTo(AdminStatus.DISABLED);
        verify(adminUserRepository).saveAndFlush(entity);
        verify(adminAuthService).evictByFirebaseUid("uid-disable-revoke");
        verify(auditService).log(eq("admin_users"), eq(adminId), eq("ADMIN_ACCOUNT_UPDATED"), eq(actorId), any());
    }

    // -------------------------------------------------------------------------
    // updateAdmin → happy path (change role + permissionOverrides)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateAdmin happy path: change role and permissionOverrides → entity updated, saved, audit ADMIN_ACCOUNT_UPDATED")
    void updateAdmin_changeRoleAndPermissions_happyPath() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        AdminUserEntity entity = buildEntity(adminId, "uid-update", "admin.update@yadony.test", AdminRole.ADMIN, AdminStatus.ACTIVE);
        when(adminUserRepository.findById(adminId)).thenReturn(Optional.of(entity));
        UpdateAdminRequest req = new UpdateAdminRequest(AdminRole.SUPPORT, Map.of("MANAGE_USERS", true), null);

        AdminUserEntity result = adminAccountService.updateAdmin(adminId, req, actorId);

        assertThat(result.getRole()).isEqualTo(AdminRole.SUPPORT);
        assertThat(result.getPermissionOverrides()).containsEntry("MANAGE_USERS", true);

        verify(adminUserRepository).saveAndFlush(entity);
        verify(adminAuthService).evictByFirebaseUid("uid-update");
        verify(auditService).log(
                eq("admin_users"),
                eq(adminId),
                eq("ADMIN_ACCOUNT_UPDATED"),
                eq(actorId),
                any()
        );
    }

    // -------------------------------------------------------------------------
    // findOrThrow → admin not found → ADMIN_NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resetPassword on non-existent admin → YadonyBusinessException ADMIN_NOT_FOUND")
    void resetPassword_adminNotFound_throws() {
        UUID adminId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        when(adminUserRepository.findById(adminId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminAccountService.resetPassword(adminId, actorId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("ADMIN_NOT_FOUND");
    }
}
