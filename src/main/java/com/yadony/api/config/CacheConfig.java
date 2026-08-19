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
                .expireAfterWrite(5, TimeUnit.MINUTES));

        // Per-cache overrides
        // adminAuthz: short-lived (30 s), small (200 entries) — auth hot path
        manager.registerCustomCache("adminAuthz",
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
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
                            .build());
        }

        // platform-settings : une seule entree ("all"), TTL courte de 30 s ET eviction
        // explicite a l'ecriture. Contrairement aux caches "/me" ci-dessus, celui-ci a un
        // point d'ecriture UNIQUE (PUT /admin/settings) : l'eviction exhaustive y est donc
        // fiable, et la TTL n'est qu'un filet en cas d'ecriture faite hors application.
        //
        // ⚠️ registerCustomCache et NON setCacheNames : setCacheNames s'execute apres et fait
        // cacheMap.keySet().retainAll(customCacheNames) — les caches personnalises survivent,
        // mais une entree ajoutee a la liste setCacheNames prendrait le spec par defaut
        // (5 min), pas cette TTL.
        manager.registerCustomCache("platform-settings",
                Caffeine.newBuilder()
                        .maximumSize(1)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .build());

        // Standard caches that use the default spec
        manager.setCacheNames(java.util.List.of(
                "announcements-search",
                "estimation-corridor",
                "trips-summary",
                "exchange-rates"
        ));

        return manager;
    }
}
