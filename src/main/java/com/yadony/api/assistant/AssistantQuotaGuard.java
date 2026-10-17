package com.yadony.api.assistant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.assistant.quota.AssistantUserDailyUsageStore;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Questions par utilisateur et par jour UTC : protège le coût des fournisseurs (spec §3.6). Le compte est
 * en base ({@link AssistantUserDailyUsageStore}, V329) : il survit aux redémarrages et reste juste sur
 * plusieurs instances. Une question refusée n'est pas comptée.
 *
 * <p>Base injoignable : on laisse passer (fail-open, WARN au plus une fois par minute) pour que
 * l'utilisateur ait sa réponse, mais les questions accordées ainsi sont comptées en mémoire contre le même
 * quota du jour : une panne de base ne lève pas le plafond, elle le rend seulement local à l'instance.
 */
@Component
public class AssistantQuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(AssistantQuotaGuard.class);
    static final Duration STORE_WARN_INTERVAL = Duration.ofMinutes(1);

    private final AssistantUserDailyUsageStore store;
    private final PlatformSettingsService settings;
    private final Clock clock;
    /** Questions accordées pendant une panne de la base seulement. */
    private final Cache<String, AtomicInteger> fallback =
            Caffeine.newBuilder().maximumSize(50_000).expireAfterWrite(Duration.ofHours(25)).build();
    private final AtomicReference<Instant> lastStoreWarn = new AtomicReference<>();

    @Autowired
    public AssistantQuotaGuard(AssistantUserDailyUsageStore store, PlatformSettingsService settings) {
        this(store, settings, Clock.systemUTC());
    }

    public AssistantQuotaGuard(AssistantUserDailyUsageStore store, PlatformSettingsService settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return les questions restantes aujourd'hui, celle-ci déduite ; 429 si le quota est atteint */
    public int consume(String uid) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int quota = settings.assistantDailyQuota();
        int used;
        try {
            used = store.tryConsume(uid, today, quota);
        } catch (DataAccessException | TransactionException e) {
            warnStoreDown(e);
            used = consumeInMemory(uid + ":" + today, quota);
        }
        if (used == AssistantUserDailyUsageStore.REFUSED) {
            String retryAfter = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS, "assistant-quota-exceeded",
                    "Too Many Requests", "Vous avez posé beaucoup de questions aujourd'hui. Réessayez demain.",
                    Map.of("retryAfter", retryAfter));
        }
        return quota - used;
    }

    private int consumeInMemory(String key, int quota) {
        AtomicInteger counter = fallback.get(key, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= quota) return AssistantUserDailyUsageStore.REFUSED;
            if (counter.compareAndSet(current, current + 1)) return current + 1;
        }
    }

    /** Un WARN par minute au plus : une base en panne ne doit pas noyer les journaux. */
    private void warnStoreDown(RuntimeException e) {
        Instant now = clock.instant();
        Instant last = lastStoreWarn.get();
        if ((last == null || !now.isBefore(last.plus(STORE_WARN_INTERVAL))) && lastStoreWarn.compareAndSet(last, now)) {
            log.warn("Assistant : quota des questions injoignable en base, question comptée en mémoire : {}",
                    e.getClass().getSimpleName());
        }
    }
}
