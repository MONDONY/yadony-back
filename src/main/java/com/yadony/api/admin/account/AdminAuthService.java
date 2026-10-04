package com.yadony.api.admin.account;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves and caches admin authorities from Firebase UID.
 *
 * Task 4 — AdminAuthService (authorities + cache)
 *
 * Cache: "adminAuthz" (TTL 30 s, max 10 000 entries — see CacheConfig).
 * Cache key is the firebaseUid string.
 * Eviction is triggered on any account mutation via evict(UUID adminId).
 */
@Service
public class AdminAuthService {

    private final AdminUserRepository adminUserRepository;

    public AdminAuthService(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    /**
     * Resolves effective authorities for the given Firebase UID.
     *
     * Returns Optional.empty() when:
     * - No admin account exists for this UID
     * - The account status is DISABLED
     *
     * Otherwise returns an AdminAuthorities containing:
     * - All effective permissions as SimpleGrantedAuthority(permission.name())
     * - Always: ROLE_ADMIN
     * - If SUPER_ADMIN: additionally ROLE_SUPER_ADMIN
     *
     * Result is cached under "adminAuthz" with 30-second TTL, Optional.empty() included:
     * the auth filter calls this on every request, mostly for mobile users who are not
     * admins. Creating or reactivating an admin evicts the entry (evictByFirebaseUid).
     */
    @Cacheable(value = "adminAuthz", key = "#firebaseUid")
    public Optional<AdminAuthorities> resolve(String firebaseUid) {
        Optional<AdminUserEntity> entityOpt = adminUserRepository.findByFirebaseUid(firebaseUid);

        if (entityOpt.isEmpty()) {
            return Optional.empty();
        }

        AdminUserEntity entity = entityOpt.get();

        if (entity.getStatus() == AdminStatus.DISABLED) {
            return Optional.empty();
        }

        Set<GrantedAuthority> authorities = buildAuthorities(entity);

        return Optional.of(new AdminAuthorities(
                entity.getRole(),
                authorities,
                Boolean.TRUE.equals(entity.getMustChangePassword()),
                entity.getEmail(),
                entity.getId()
        ));
    }

    /**
     * Evicts the cached authorities entry for the given admin (by admin UUID).
     * Must be called after any mutation of the admin account (role change, status change, etc.).
     *
     * Note: the cache key is firebaseUid, but eviction is by adminId as the caller
     * (account mutation service) knows the admin's UUID, not necessarily the firebaseUid.
     * This implementation loads the entity to obtain the firebaseUid for precise eviction.
     * If the entity is not found (e.g., just deleted), no eviction is needed.
     */
    @CacheEvict(value = "adminAuthz", allEntries = false, key = "#root.target.resolveFirebaseUid(#adminId)")
    public void evict(UUID adminId) {
        // Cache eviction is handled declaratively via @CacheEvict.
        // The key expression delegates to resolveFirebaseUid to look up the Firebase UID.
    }

    /**
     * Helper method used by the @CacheEvict SpEL expression to resolve the firebaseUid
     * for a given adminId so cache eviction targets the correct entry.
     * Returns an empty string if the entity is not found (safe: no matching cache entry).
     */
    public String resolveFirebaseUid(UUID adminId) {
        return adminUserRepository.findById(adminId)
                .map(AdminUserEntity::getFirebaseUid)
                .orElse("");
    }

    /**
     * Evicts the cached authorities entry directly by firebaseUid.
     * Prefer this over evict(UUID) when the caller already holds the firebaseUid —
     * in particular after a soft-delete where findById would return empty (due to
     * {@code @Where(deleted_at IS NULL)}), which would cause evict(UUID) to miss.
     */
    @CacheEvict(value = "adminAuthz", key = "#firebaseUid")
    public void evictByFirebaseUid(String firebaseUid) {
        // no-op — @CacheEvict annotation handles eviction
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Set<GrantedAuthority> buildAuthorities(AdminUserEntity entity) {
        // Start with effective permissions
        Set<AdminPermission> permissions = AdminPermissions.effective(
                entity.getRole(),
                entity.getPermissionOverrides()
        );

        Set<GrantedAuthority> authorities = permissions.stream()
                .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p.name()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Always grant ROLE_ADMIN
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));

        // SUPER_ADMIN also gets ROLE_SUPER_ADMIN
        if (entity.getRole() == AdminRole.SUPER_ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        }

        return authorities;
    }
}
