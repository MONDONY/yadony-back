package com.yadony.api.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // Default spec: 5-minute TTL, max 500 entries
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .recordStats());

        // Per-cache overrides
        // adminAuthz: short-lived (30 s), small (200 entries) — auth hot path
        manager.registerCustomCache("adminAuthz",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .recordStats()
                        .build());

        // bids-me / traveler-bids-me / negotiations-me: TTL très courte (8 s),
        // sans éviction manuelle. Ces endpoints "/me" sont tirés en rafale par
        // l'app (tab switch, cold start) et se sont révélés être la première
        // cause de saturation du rate-limit nginx en usage réel. La donnée est
        // bilatérale (expéditeur + voyageur) et mutée par une dizaine de points
        // d'entrée différents : une éviction manuelle exhaustive serait plus
        // fragile (un oubli = cache jamais invalidé) qu'une expiration courte
        // assumée — le client tolère déjà ce délai (throttle 3 s au retour
        // d'onglet, cf. dony_app MainShell/ActivitesHubScreen).
        for (String cacheName : java.util.List.of("bids-me", "traveler-bids-me", "negotiations-me")) {
            manager.registerCustomCache(cacheName,
                    Caffeine.newBuilder()
                            .maximumSize(2000)
                            .expireAfterWrite(8, TimeUnit.SECONDS)
                            .recordStats()
                            .build());
        }

        // City autocomplete and popular corridors are hot read-mostly endpoints
        // during onboarding/search. Data changes only when city data is reloaded
        // or a corridor is used, so short bounded caches protect Postgres without
        // introducing cross-node consistency concerns.
        manager.registerCustomCache("city-search",
                Caffeine.newBuilder()
                        .maximumSize(5000)
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .recordStats()
                        .build());
        manager.registerCustomCache("popular-corridors",
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(1, TimeUnit.MINUTES)
                        .recordStats()
                        .build());

        // Standard caches that use the default spec
        manager.setCacheNames(java.util.List.of(
                "announcements-search",
                "estimation-corridor",
                "trips-summary"
        ));

        return manager;
    }
}
