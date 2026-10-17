package com.yadony.api.assistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Santé des fournisseurs. Les compteurs du jour (UTC) sont en base ({@link ProviderDailyUsageStore},
 * V327) : ils survivent aux redémarrages, et la limite par jour reste juste après un déploiement. Les
 * mises de côté (429, 401…) et le dernier statut restent en mémoire : ils repartent à zéro au
 * redémarrage, ce qui ne coûte au pire qu'un essai refusé de plus.
 *
 * <p>Base injoignable : on laisse passer (fail-open, WARN au plus une fois par minute) pour que
 * l'utilisateur ait sa réponse, mais les essais accordés ainsi sont comptés en mémoire contre la même
 * limite du jour : une panne de base ne lève pas le plafond, elle le rend seulement local à
 * l'instance. Le plafond de dépense posé chez le fournisseur reste le dernier filet.
 */
@Component
public class ProviderHealthRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthRegistry.class);
    static final Duration COOLDOWN_QUOTA = Duration.ofSeconds(60);
    static final Duration COOLDOWN_AUTH = Duration.ofMinutes(10);
    static final Duration STORE_WARN_INTERVAL = Duration.ofMinutes(1);

    private final ProviderDailyUsageStore usage;
    private final Clock clock;
    private final Map<UUID, Health> health = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastStoreWarn = new AtomicReference<>();

    @Autowired
    public ProviderHealthRegistry(ProviderDailyUsageStore usage) {
        this(usage, Clock.systemUTC());
    }

    public ProviderHealthRegistry(ProviderDailyUsageStore usage, Clock clock) {
        this.usage = usage;
        this.clock = clock;
    }

    /**
     * Réserve un essai si le fournisseur n'est pas mis de côté et que la limite du jour, lue en base,
     * n'est pas atteinte. La réservation est atomique en base : deux questions simultanées, même sur
     * deux instances, ne dépassent pas la limite.
     */
    public boolean tryAcquire(LlmProviderConfig provider) {
        Health h = current(provider.id());
        synchronized (h) {
            if (isSetAside(h)) return false;
        }
        int limit = provider.dailyRequestLimit() == null ? ProviderDailyUsageStore.NO_LIMIT : provider.dailyRequestLimit();
        LocalDate today = today();
        try {
            return usage.tryReserve(provider.id(), today, limit);
        } catch (DataAccessException | TransactionException e) {
            warnStoreDown("réservation", e);
            synchronized (h) {
                rollFallbackDay(h, today);
                if (h.fallbackRequests >= limit) return false;
                h.fallbackRequests++;
                return true;
            }
        }
    }

    public void recordSuccess(UUID id) {
        Health h = current(id);
        synchronized (h) {
            h.lastStatus = 200;
            h.lastError = null;
        }
        try {
            usage.addSuccess(id, today());
        } catch (DataAccessException | TransactionException e) {
            warnStoreDown("succès", e);
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

    /**
     * Lève la mise de côté et efface la dernière erreur, sans toucher aux compteurs du jour. Appelé quand
     * l'admin corrige la clé, l'adresse ou le modèle, ou supprime le fournisseur : après un 401, il ne
     * doit pas attendre les 10 minutes de mise de côté. Les compteurs restent : changer la clé ne doit
     * pas rouvrir la limite du jour (plafond de coût).
     */
    public void reset(UUID id) {
        Health h = current(id);
        synchronized (h) {
            h.cooldownUntil = null;
            h.lastStatus = null;
            h.lastError = null;
        }
    }

    public ProviderStats stats(UUID id) {
        return stats(List.of(id)).get(id);
    }

    /** Stats de plusieurs fournisseurs : une seule lecture en base pour les compteurs du jour. */
    public Map<UUID, ProviderStats> stats(Collection<UUID> ids) {
        LocalDate today = today();
        Map<UUID, ProviderDailyUsageStore.DailyCount> counts = usage.read(ids, today);
        Map<UUID, ProviderStats> result = new HashMap<>();
        for (UUID id : ids) {
            ProviderDailyUsageStore.DailyCount count = counts.get(id);
            int requests = count == null ? 0 : count.requests();
            int successes = count == null ? 0 : count.successes();
            Health h = current(id);
            synchronized (h) {
                rollFallbackDay(h, today);
                Instant cooldown = isSetAside(h) ? h.cooldownUntil : null;
                result.put(id, new ProviderStats(requests + h.fallbackRequests, successes, h.lastStatus,
                        h.lastError, cooldown));
            }
        }
        return result;
    }

    private boolean isSetAside(Health h) {
        return h.cooldownUntil != null && clock.instant().isBefore(h.cooldownUntil);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void rollFallbackDay(Health h, LocalDate today) {
        if (!today.equals(h.fallbackDay)) {
            h.fallbackDay = today;
            h.fallbackRequests = 0;
        }
    }

    private Health current(UUID id) {
        return health.computeIfAbsent(id, k -> new Health());
    }

    /** Un WARN par minute au plus : une base en panne ne doit pas noyer les journaux. */
    private void warnStoreDown(String operation, RuntimeException e) {
        Instant now = clock.instant();
        Instant last = lastStoreWarn.get();
        if ((last == null || !now.isBefore(last.plus(STORE_WARN_INTERVAL))) && lastStoreWarn.compareAndSet(last, now)) {
            log.warn("Assistant : compteurs des fournisseurs injoignables en base ({}), essai accordé sur le compteur "
                    + "en mémoire : {}", operation, e.getClass().getSimpleName());
        }
    }

    public record ProviderStats(int requestsToday, int successesToday, Integer lastStatus, String lastError,
                                Instant cooldownUntil) {
    }

    private static final class Health {
        Integer lastStatus;
        String lastError;
        Instant cooldownUntil;
        /** Essais accordés pendant une panne de la base, comptés contre la limite du jour. */
        LocalDate fallbackDay;
        int fallbackRequests;
    }
}
