package com.yadony.api.admin.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for AdminUserEntity (Task 3 — RBAC).
 * Soft-delete filtering is handled by @Where clause on AdminUserEntity.
 */
@Repository
public interface AdminUserRepository extends JpaRepository<AdminUserEntity, UUID> {

    /**
     * Find admin user by Firebase UID.
     */
    Optional<AdminUserEntity> findByFirebaseUid(String firebaseUid);

    Optional<AdminUserEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    long countByRole(AdminRole role);

    /**
     * Count admin users by role and status (soft-deleted are excluded by @Where).
     */
    long countByRoleAndStatus(AdminRole role, AdminStatus status);

    /**
     * Find admin users by role and status with pagination (soft-deleted are excluded by @Where).
     */
    Page<AdminUserEntity> findByRoleAndStatus(AdminRole role, AdminStatus status, Pageable pageable);

    /**
     * Find admin users by role with pagination (soft-deleted are excluded by @Where).
     */
    Page<AdminUserEntity> findByRole(AdminRole role, Pageable pageable);

    /**
     * Find admin users by status with pagination (soft-deleted are excluded by @Where).
     */
    Page<AdminUserEntity> findByStatus(AdminStatus status, Pageable pageable);

    /**
     * Find the first admin user matching the given role and status (used by break-glass bootstrap).
     * Soft-deleted entities are excluded by @Where clause on AdminUserEntity.
     */
    Optional<AdminUserEntity> findFirstByRoleAndStatus(AdminRole role, AdminStatus status);

    /** Dernière consultation de la cloche ; vide si jamais consultée. */
    @Query("SELECT a.notificationsSeenAt FROM AdminUserEntity a WHERE a.id = :adminId")
    Optional<LocalDateTime> findNotificationsSeenAt(@Param("adminId") UUID adminId);

    /**
     * Avance la date de consultation de la cloche, jamais en arrière : la condition est dans
     * l'UPDATE, deux onglets qui marquent « vu » en même temps ne peuvent pas faire reculer la
     * date. Pas de {@code clearAutomatically} : il détacherait les écritures en attente.
     *
     * @return 1 si la date a avancé, 0 sinon
     */
    @Modifying
    @Transactional
    @Query("UPDATE AdminUserEntity a SET a.notificationsSeenAt = :upTo WHERE a.id = :adminId "
            + "AND (a.notificationsSeenAt IS NULL OR a.notificationsSeenAt < :upTo)")
    int advanceNotificationsSeenAt(@Param("adminId") UUID adminId, @Param("upTo") LocalDateTime upTo);
}
