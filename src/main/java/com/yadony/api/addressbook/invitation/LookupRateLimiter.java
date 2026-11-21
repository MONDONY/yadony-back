package com.yadony.api.addressbook.invitation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Plafond d'appels par heure glissante et par utilisateur, pour qu'on ne puisse pas énumérer les
 * comptes. Deux instances existent (voir {@code UsernameRateLimitConfig}) : les recherches et envois par
 * identifiant (30 par heure, seau partagé) et les vérifications de disponibilité (120 par heure). Mémoire
 * locale (Caffeine, pas de Redis en MVP) : une seule instance d'API tourne, avec plusieurs le plafond
 * effectif serait multiplié.
 */
public class LookupRateLimiter {

    /** Plafond des recherches et envois par identifiant. */
    public static final int MAX_PER_HOUR = 30;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final int maxPerHour;
    private final Cache<UUID, Deque<Long>> calls = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(WINDOW)
            .build();

    public LookupRateLimiter(Clock clock, int maxPerHour) {
        this.clock = clock;
        this.maxPerHour = maxPerHour;
    }

    /** Compte un appel ; {@code false} si l'utilisateur a déjà épuisé ses appels de l'heure. */
    public boolean tryAcquire(UUID userId) {
        long now = clock.millis();
        Deque<Long> window = calls.get(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW.toMillis()) {
                window.pollFirst();
            }
            if (window.size() >= maxPerHour) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
