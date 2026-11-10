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
import org.springframework.context.event.EventListener;
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

    @Test
    @DisplayName("l'écouteur est un @EventListener qui vide tout le cache announcements-search")
    void listenerIsDeclaredAsEvictAll() throws Exception {
        var method = AnnouncementService.class.getMethod("onTravelerStatusChanged", TravelerStatusChangedEvent.class);

        assertThat(method.isAnnotationPresent(EventListener.class)).isTrue();
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
}
