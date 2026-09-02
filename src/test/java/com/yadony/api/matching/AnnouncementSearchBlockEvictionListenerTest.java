package com.yadony.api.matching;

import com.yadony.api.auth.events.UserBlockChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnouncementSearchBlockEvictionListenerTest {

    @Mock CacheManager cacheManager;
    @Mock Cache cache;

    @Test
    void blocage_purgeLaRechercheDeTrajets() {
        when(cacheManager.getCache("announcements-search")).thenReturn(cache);
        var listener = new AnnouncementSearchBlockEvictionListener(cacheManager);

        listener.onUserBlockChanged(new UserBlockChangedEvent(UUID.randomUUID(), UUID.randomUUID(), true));

        verify(cache).clear();
    }

    /** Symétrique : au déblocage, les trajets doivent réapparaître sans attendre le TTL. */
    @Test
    void deblocage_purgeAussi() {
        when(cacheManager.getCache("announcements-search")).thenReturn(cache);
        var listener = new AnnouncementSearchBlockEvictionListener(cacheManager);

        listener.onUserBlockChanged(new UserBlockChangedEvent(UUID.randomUUID(), UUID.randomUUID(), false));

        verify(cache).clear();
    }

    @Test
    void cacheAbsent_neLevePas() {
        when(cacheManager.getCache("announcements-search")).thenReturn(null);
        var listener = new AnnouncementSearchBlockEvictionListener(cacheManager);

        assertThatCode(() -> listener.onUserBlockChanged(
                new UserBlockChangedEvent(UUID.randomUUID(), UUID.randomUUID(), true)))
                .doesNotThrowAnyException();
    }
}
