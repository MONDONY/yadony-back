package com.yadony.api.config;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bean SEPARE de {@link PlatformSettingsService}, et c'est le point important.
 *
 * <p>{@code @Cacheable} passe par un proxy : une methode annotee appelee depuis une autre
 * methode du MEME bean court-circuite ce proxy et frappe la base a chaque fois, en silence.
 * Si {@code commissionRate()}, {@code smsEnabled()} etc. vivaient dans la meme classe que
 * le {@code @Cacheable}, aucun d'eux ne serait cache — et {@code /config/commission-rate},
 * appele par l'application mobile a chaque demarrage, interrogerait PostgreSQL a chaque fois.
 */
@Service
public class PlatformSettingsCache {

    private final PlatformSettingRepository repository;

    public PlatformSettingsCache(PlatformSettingRepository repository) {
        this.repository = repository;
    }

    /** Cle unique : les quatre parametres tiennent en une entree, lue ensemble. */
    @Cacheable(cacheNames = "platform-settings", key = "'all'")
    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> values = new LinkedHashMap<>();
        for (PlatformSettingEntity entity : repository.findAll()) {
            values.put(entity.getSettingKey(), entity.getSettingValue());
        }
        return values;
    }

    @CacheEvict(cacheNames = "platform-settings", allEntries = true)
    public void evict() {
        // L'annotation fait tout le travail.
    }
}
