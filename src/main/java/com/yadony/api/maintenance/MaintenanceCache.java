package com.yadony.api.maintenance;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.Set;

/**
 * Bean séparé de {@link MaintenanceService} pour que {@code @Cacheable} passe par le proxy
 * (même raison que {@code PlatformSettingsCache}). Lu à CHAQUE requête de l'API.
 */
@Service
public class MaintenanceCache {

    static final String CACHE = "maintenance";

    private final MaintenanceStateRepository states;
    private final MaintenanceExemptionRepository exemptions;

    public MaintenanceCache(MaintenanceStateRepository states, MaintenanceExemptionRepository exemptions) {
        this.states = states;
        this.exemptions = exemptions;
    }

    @Cacheable(cacheNames = CACHE, key = "'state'")
    @Transactional(readOnly = true)
    public MaintenanceSnapshot snapshot() {
        return states.findById(MaintenanceStateEntity.SINGLETON_ID)
                .map(s -> new MaintenanceSnapshot(s.isEnabled(), s.getMessage(),
                        s.getEstimatedEndAt() == null ? null : s.getEstimatedEndAt().toInstant(ZoneOffset.UTC),
                        s.getStartedAt() == null ? null : s.getStartedAt().toInstant(ZoneOffset.UTC),
                        s.isEnabled() ? Set.copyOf(exemptions.findExemptFirebaseUids()) : Set.of()))
                .orElse(MaintenanceSnapshot.OFF);
    }

    @CacheEvict(cacheNames = CACHE, allEntries = true)
    public void evict() {
        // L'annotation fait tout le travail.
    }
}
