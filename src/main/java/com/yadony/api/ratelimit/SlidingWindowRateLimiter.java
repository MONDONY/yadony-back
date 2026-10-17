package com.yadony.api.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * Compteur à fenêtre glissante approchée, en mémoire (Caffeine, pas de Redis en MVP).
 *
 * <p>Deux compteurs par clé : la fenêtre en cours et la précédente. L'estimation
 * pondère la précédente par la part encore couverte de la fenêtre glissante, ce qui
 * évite le double débit d'une fenêtre fixe à sa frontière, pour deux entiers par clé.
 *
 * <p>Une seule instance d'API tourne : la mémoire locale suffit. Avec plusieurs
 * instances, chacune appliquerait sa limite et le plafond effectif serait multiplié.
 */
class SlidingWindowRateLimiter {

    private final Clock clock;
    private final Cache<String, Window> windows;

    SlidingWindowRateLimiter(Clock clock, Duration longestWindow) {
        this.clock = clock;
        // Deux fenêtres complètes suffisent à l'estimation ; au-delà la clé est oubliée.
        this.windows = Caffeine.newBuilder()
                .maximumSize(500_000)
                .expireAfterAccess(longestWindow.multipliedBy(2))
                .build();
    }

    /**
     * Compte une requête pour {@code key}. Rend vide si elle passe, sinon le délai en
     * secondes avant qu'une requête puisse repasser ; une requête refusée n'est pas comptée.
     */
    OptionalLong tryAcquire(String key, int limit, Duration window) {
        long windowMs = window.toMillis();
        long now = clock.millis();
        Window w = windows.get(key, k -> new Window());
        synchronized (w) {
            long start = now - Math.floorMod(now, windowMs);
            if (start != w.start) {
                // Fenêtre suivante : l'ancienne devient la précédente ; au-delà, plus rien.
                w.previous = start - w.start == windowMs ? w.current : 0;
                w.current = 0;
                w.start = start;
            }
            double weight = 1.0 - (double) (now - start) / windowMs;
            double estimate = w.previous * weight + w.current;
            if (estimate + 1 > limit) {
                return OptionalLong.of(retryAfterSeconds(w, limit, now, start, windowMs));
            }
            w.current++;
            return OptionalLong.empty();
        }
    }

    /**
     * Temps avant que l'estimation repasse sous la limite. Si la fenêtre en cours est
     * pleine à elle seule, il faut attendre qu'elle devienne la précédente puis que son
     * poids décroisse ; sinon c'est la décroissance de la précédente qui libère la place.
     */
    private static long retryAfterSeconds(Window w, int limit, long now, long start, long windowMs) {
        long untilNext = start + windowMs - now;
        long waitMs;
        if (w.current + 1 > limit) {
            // Fenêtre suivante : estimation = current × poids(t) + 0 ; on attend que
            // current × (1 - t/window) + 1 ≤ limit.
            double t = windowMs * (1.0 - (double) (limit - 1) / w.current);
            waitMs = untilNext + (long) Math.max(0, t);
        } else {
            // previous × (1 - (elapsed + x)/window) + current + 1 ≤ limit. Passé la
            // frontière, l'ancienne fenêtre sort du calcul et la place se libère : borné.
            double elapsed = now - start;
            double needed = windowMs * (1.0 - (double) (limit - 1 - w.current) / w.previous) - elapsed;
            waitMs = Math.min(untilNext, (long) Math.max(0, needed));
        }
        return Math.max(1, (waitMs + 999) / 1000);
    }

    private static final class Window {
        long start = Long.MIN_VALUE;
        long previous;
        long current;
    }
}
