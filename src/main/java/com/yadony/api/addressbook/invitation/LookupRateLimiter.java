package com.yadony.api.addressbook.invitation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Plafond de recherches d'identifiant : 30 par heure glissante et par utilisateur, pour qu'on ne
 * puisse pas énumérer les comptes. Mémoire locale (Caffeine, pas de Redis en MVP) : une seule
 * instance d'API tourne, avec plusieurs le plafond effectif serait multiplié.
 */
@Component
public class LookupRateLimiter {

    static final int MAX_PER_HOUR = 30;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Cache<UUID, Deque<Long>> calls = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(WINDOW)
            .build();

    @Autowired
    public LookupRateLimiter() {
        this(Clock.systemUTC());
    }

    LookupRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Compte un appel ; {@code false} si l'utilisateur a déjà épuisé ses 30 appels de l'heure. */
    public boolean tryAcquire(UUID userId) {
        long now = clock.millis();
        Deque<Long> window = calls.get(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW.toMillis()) {
                window.pollFirst();
            }
            if (window.size() >= MAX_PER_HOUR) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
