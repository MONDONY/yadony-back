package com.yadony.api.admin.notifications;

import com.yadony.api.admin.metrics.AdminQueueCounter;
import com.yadony.api.admin.metrics.AdminQueueSnapshot;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Données partagées par tous les administrateurs, mises en cache 15 secondes
 * ({@link com.yadony.api.config.CacheConfig}) : les files à traiter et les dernières entrées de
 * chaque source. Chaque panel ouvert interroge les compteurs toutes les 30 secondes ; sans ce
 * cache, dix administrateurs connectés feraient une vingtaine de requêtes par seconde pour des
 * chiffres identiques. Avec lui, le coût ne dépend plus du nombre d'administrateurs : au plus
 * une série de requêtes toutes les 15 secondes.
 *
 * <p>Le filtrage par permission et le calcul du non-lu, propres à chaque administrateur, se font
 * après le cache ({@link AdminNotificationService}). Pas d'éviction manuelle : une nouveauté met
 * au plus 15 secondes à apparaître, bien moins que l'intervalle de rafraîchissement du panel.
 */
@Component
public class AdminNotificationCache {

    public static final String CACHE = "admin-notifications";

    /** Fenêtre du fil : rien de plus vieux n'est une nouveauté. */
    static final Duration WINDOW = Duration.ofDays(30);

    /**
     * Profondeur gardée par source. Suffit au plafond du non-lu (99) et à la première page du
     * fil (100 au plus) : au-delà, la pagination par curseur interroge la base directement.
     */
    static final int RECENT_DEPTH = 100;

    private final AdminNotificationQueries queries;
    private final AdminQueueCounter queueCounter;

    public AdminNotificationCache(AdminNotificationQueries queries, AdminQueueCounter queueCounter) {
        this.queries = queries;
        this.queueCounter = queueCounter;
    }

    /** Les {@link #RECENT_DEPTH} entrées les plus récentes de la source, sur la fenêtre, triées par date décroissante. */
    @Cacheable(cacheNames = CACHE, key = "'recent:' + #type.name()")
    public List<AdminNotificationItem> recent(AdminNotificationType type) {
        return queries.find(type, Instant.now().minus(WINDOW), null, RECENT_DEPTH);
    }

    @Cacheable(cacheNames = CACHE, key = "'queues'")
    public AdminQueueSnapshot queues() {
        return queueCounter.snapshot();
    }
}
