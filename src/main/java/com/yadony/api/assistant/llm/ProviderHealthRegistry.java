package com.yadony.api.assistant.llm;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compteurs du jour et mises de côté des fournisseurs, en mémoire (repartent à zéro au
 * redémarrage, spec §3.5 et §8). Une entrée par fournisseur : le nombre reste petit.
 */
@Component
public class ProviderHealthRegistry {

    static final Duration COOLDOWN_QUOTA = Duration.ofSeconds(60);
    static final Duration COOLDOWN_AUTH = Duration.ofMinutes(10);

    private final Clock clock;
    private final Map<UUID, Health> health = new ConcurrentHashMap<>();

    @Autowired
    public ProviderHealthRegistry() {
        this(Clock.systemUTC());
    }

    public ProviderHealthRegistry(Clock clock) {
        this.clock = clock;
    }

    public boolean isAvailable(LlmProviderConfig provider) {
        Health h = current(provider.id());
        synchronized (h) {
            if (h.cooldownUntil != null && clock.instant().isBefore(h.cooldownUntil)) return false;
            return provider.dailyRequestLimit() == null || h.requests < provider.dailyRequestLimit();
        }
    }

    public void recordAttempt(UUID id) {
        Health h = current(id);
        synchronized (h) { h.requests++; }
    }

    public void recordSuccess(UUID id) {
        Health h = current(id);
        synchronized (h) {
            h.successes++;
            h.lastStatus = 200;
            h.lastError = null;
        }
    }

    public void recordFailure(UUID id, int status, String message) {
        Health h = current(id);
        synchronized (h) {
            h.lastStatus = status;
            h.lastError = message == null ? null : (message.length() > 200 ? message.substring(0, 200) : message);
            if (status == 429) h.cooldownUntil = clock.instant().plus(COOLDOWN_QUOTA);
            // Une adresse non publique ne se corrige pas en réessayant : même traitement qu'une clé refusée.
            if (status == 401 || status == 403 || status == LlmCallException.BLOCKED_ADDRESS) {
                h.cooldownUntil = clock.instant().plus(COOLDOWN_AUTH);
            }
        }
    }

    public ProviderStats stats(UUID id) {
        Health h = current(id);
        synchronized (h) {
            Instant cooldown = h.cooldownUntil != null && clock.instant().isBefore(h.cooldownUntil) ? h.cooldownUntil : null;
            return new ProviderStats(h.requests, h.successes, h.lastStatus, h.lastError, cooldown);
        }
    }

    private Health current(UUID id) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Health h = health.computeIfAbsent(id, k -> new Health(today));
        synchronized (h) {
            if (!h.day.equals(today)) {
                h.day = today;
                h.requests = 0;
                h.successes = 0;
            }
        }
        return h;
    }

    public record ProviderStats(int requestsToday, int successesToday, Integer lastStatus, String lastError,
                                Instant cooldownUntil) {
    }

    private static final class Health {
        LocalDate day;
        int requests;
        int successes;
        Integer lastStatus;
        String lastError;
        Instant cooldownUntil;

        Health(LocalDate day) { this.day = day; }
    }
}
