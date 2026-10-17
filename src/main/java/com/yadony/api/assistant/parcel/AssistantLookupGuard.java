package com.yadony.api.assistant.parcel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Numéros de suivi saisis à tort, par utilisateur et par jour UTC. Passé la limite, plus aucune
 * recherche : la réponse reste « numéro incorrect », que le numéro existe ou non, pour qu'on ne
 * puisse pas sonder les numéros des autres à coups d'essais.
 */
@Component
public class AssistantLookupGuard {

    static final int MAX_FAILED_LOOKUPS_PER_DAY = 10;

    private final Clock clock;
    private final Cache<String, AtomicInteger> failures =
            Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofHours(25)).build();

    @Autowired
    public AssistantLookupGuard() {
        this(Clock.systemUTC());
    }

    AssistantLookupGuard(Clock clock) {
        this.clock = clock;
    }

    public boolean blocked(String uid) {
        AtomicInteger count = failures.getIfPresent(key(uid));
        return count != null && count.get() >= MAX_FAILED_LOOKUPS_PER_DAY;
    }

    public void recordFailure(String uid) {
        failures.get(key(uid), k -> new AtomicInteger()).incrementAndGet();
    }

    private String key(String uid) {
        return uid + ":" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
