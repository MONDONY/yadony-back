package com.yadony.api.assistant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Questions par utilisateur et par jour UTC, en mémoire : protège les offres gratuites (spec §3.6). */
@Component
public class AssistantQuotaGuard {

    private final PlatformSettingsService settings;
    private final Clock clock;
    private final Cache<String, AtomicInteger> counters =
            Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofHours(25)).build();

    @Autowired
    public AssistantQuotaGuard(PlatformSettingsService settings) {
        this(settings, Clock.systemUTC());
    }

    public AssistantQuotaGuard(PlatformSettingsService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public int consume(String uid) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int quota = settings.assistantDailyQuota();
        int used = counters.get(uid + ":" + today, k -> new AtomicInteger()).incrementAndGet();
        if (used > quota) {
            String retryAfter = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS, "assistant-quota-exceeded",
                    "Too Many Requests", "Vous avez posé beaucoup de questions aujourd'hui. Réessayez demain.",
                    Map.of("retryAfter", retryAfter));
        }
        return quota - used;
    }
}
