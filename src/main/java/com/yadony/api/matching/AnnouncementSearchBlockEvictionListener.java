package com.yadony.api.matching;

import com.yadony.api.auth.events.UserBlockChangedEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Purge le cache {@code announcements-search} dès qu'une relation de blocage change.
 *
 * <p>La clé du cache inclut le viewer, mais pas l'état de ses blocages : après un
 * blocage, l'app relance la recherche et le backend lui resservait la page mise en
 * cache, avec les trajets du compte bloqué, jusqu'au TTL Caffeine (5 min). C'est la
 * cause du « je viens de bloquer et je vois encore ses annonces ».
 *
 * <p>Éviction totale plutôt que ciblée : les clés sont composées de tous les filtres
 * de recherche, impossible d'énumérer celles d'un viewer. Un blocage est rare, le
 * coût d'un rechauffement complet est négligeable.
 *
 * <p>Communication inter-packages par Spring Events (pas d'injection de
 * {@code BlockService} ici). {@code AFTER_COMMIT} : la relation est committée avant
 * la purge, donc la prochaine recherche lit bien le nouvel état.
 */
@Component
public class AnnouncementSearchBlockEvictionListener {

    static final String CACHE_NAME = "announcements-search";

    private final CacheManager cacheManager;

    public AnnouncementSearchBlockEvictionListener(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserBlockChanged(UserBlockChangedEvent event) {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }
}
