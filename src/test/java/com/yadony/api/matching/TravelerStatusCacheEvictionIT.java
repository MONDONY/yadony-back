package com.yadony.api.matching;

import com.yadony.api.travelerstatus.TravelerStatus;
import com.yadony.api.travelerstatus.events.TravelerStatusChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un voyageur qui change de statut doit voir sa pastille à jour tout de suite dans la recherche :
 * sans éviction, le cache {@code announcements-search} (5 min) servirait l'ancien statut.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Changement de statut du voyageur : éviction du cache de recherche")
class TravelerStatusCacheEvictionIT {

    @Autowired CacheManager cacheManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("l'écouteur s'exécute après le commit (ou tout de suite hors transaction) et vide tout le cache")
    void listenerIsDeclaredAsEvictAll() throws Exception {
        var method = AnnouncementService.class.getMethod("onTravelerStatusChanged", TravelerStatusChangedEvent.class);

        TransactionalEventListener listener = method.getAnnotation(TransactionalEventListener.class);
        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isTrue();
        CacheEvict evict = method.getAnnotation(CacheEvict.class);
        assertThat(evict).isNotNull();
        assertThat(evict.value()).containsExactly("announcements-search");
        assertThat(evict.allEntries()).isTrue();
    }

    @Test
    @DisplayName("publier l'événement vide réellement le cache de recherche")
    void publishingTheEventClearsTheSearchCache() {
        Cache cache = cacheManager.getCache("announcements-search");
        assertThat(cache).isNotNull();
        cache.put("une-recherche", "resultat-perime");
        assertThat(cache.get("une-recherche")).isNotNull();

        publisher.publishEvent(new TravelerStatusChangedEvent(UUID.randomUUID(), TravelerStatus.PROFESSIONAL));

        assertThat(cache.get("une-recherche")).isNull();
    }

    /**
     * Évincer avant le commit laissait une fenêtre : une recherche lancée entre l'éviction et le commit
     * remettait l'ancien statut en cache pour 5 minutes.
     */
    @Test
    @DisplayName("dans une transaction, le cache n'est vidé qu'après le commit")
    void insideATransaction_theCacheIsClearedOnlyAfterCommit() {
        Cache cache = cacheManager.getCache("announcements-search");
        assertThat(cache).isNotNull();
        cache.put("une-recherche", "resultat-perime");

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            publisher.publishEvent(new TravelerStatusChangedEvent(UUID.randomUUID(), TravelerStatus.INDIVIDUAL));
            assertThat(cache.get("une-recherche")).as("pas d'éviction avant le commit").isNotNull();
        });

        assertThat(cache.get("une-recherche")).as("évincé après le commit").isNull();
    }

    @Test
    @DisplayName("transaction annulée : rien n'est évincé")
    void rolledBackTransaction_leavesTheCacheUntouched() {
        Cache cache = cacheManager.getCache("announcements-search");
        cache.put("une-recherche", "resultat-valide");

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            publisher.publishEvent(new TravelerStatusChangedEvent(UUID.randomUUID(), TravelerStatus.INDIVIDUAL));
            tx.setRollbackOnly();
        });

        assertThat(cache.get("une-recherche")).isNotNull();
        cache.clear();
    }
}
