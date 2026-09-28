package com.yadony.api.admin.account;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin user account entity for RBAC (Task 3).
 * Extends BaseEntity for id, created_at, updated_at, deleted_at (soft delete).
 * Enforces soft-delete convention via @Where clause.
 */
@Entity
@Table(name = "admin_users")
@Where(clause = "deleted_at IS NULL")
public class AdminUserEntity extends BaseEntity {

    @Column(name = "firebase_uid", nullable = false, unique = true, length = 128)
    private String firebaseUid;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "role", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AdminRole role;

    @Column(name = "status", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private AdminStatus status;

    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword;

    @Convert(converter = PermissionOverridesConverter.class)
    @Column(name = "permission_overrides", nullable = false)
    private Map<String, Boolean> permissionOverrides = new HashMap<>();

    @Column(name = "created_by")
    private java.util.UUID createdBy;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /**
     * Dernière consultation de la cloche du panel (UTC). NULL = jamais consultée. Écrite
     * uniquement par {@link AdminUserRepository#advanceNotificationsSeenAt}, qui ne recule jamais.
     */
    @Column(name = "notifications_seen_at")
    private LocalDateTime notificationsSeenAt;

    // Constructors
    public AdminUserEntity() {
        this.mustChangePassword = true;
        this.permissionOverrides = new HashMap<>();
        this.status = AdminStatus.ACTIVE;
    }

    public AdminUserEntity(String firebaseUid, String email, AdminRole role) {
        this();
        this.firebaseUid = firebaseUid;
        this.email = email;
        this.role = role;
    }

    // Getters & setters
    public String getFirebaseUid() { return firebaseUid; }
    public void setFirebaseUid(String firebaseUid) { this.firebaseUid = firebaseUid; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public AdminRole getRole() { return role; }
    public void setRole(AdminRole role) { this.role = role; }

    public AdminStatus getStatus() { return status; }
    public void setStatus(AdminStatus status) { this.status = status; }

    public Boolean getMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(Boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public Map<String, Boolean> getPermissionOverrides() { return permissionOverrides; }
    public void setPermissionOverrides(Map<String, Boolean> permissionOverrides) { this.permissionOverrides = permissionOverrides; }

    public java.util.UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(java.util.UUID createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(OffsetDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public LocalDateTime getNotificationsSeenAt() { return notificationsSeenAt; }
}
