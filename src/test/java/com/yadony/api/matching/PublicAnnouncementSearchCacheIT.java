package com.yadony.api.matching;

import com.yadony.api.common.PageResponse;
import com.yadony.api.matching.dto.PublicAnnouncementCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Le cache {@code public-announcements-search} est clé sur le record de critères normalisés :
 * deux saisies équivalentes ("Paris" et " paris ") ne lisent la base qu'une fois.
 */
@SpringBootTest
@ActiveProfiles("test")
class PublicAnnouncementSearchCacheIT {

    private static final String CACHE = "public-announcements-search";

    @Autowired private PublicAnnouncementSearchService service;
    @Autowired private CacheManager cacheManager;
    @SpyBean private AnnouncementService announcementService;

    @BeforeEach
    void resetCachesAndSpy() {
        assertThat(cacheManager.getCache(CACHE)).isNotNull();
        cacheManager.getCache(CACHE).clear();
        cacheManager.getCache("announcements-search").clear();
        Mockito.clearInvocations(announcementService);
    }

    @Test
    void deuxSaisiesEquivalentesNeLisentLaBaseQuUneFois() {
        LocalDate today = LocalDate.now();
        PublicAnnouncementQuery first = PublicAnnouncementQuery.of(
                "Paris", "Dakar", null, null, null, null, null, null, null, null, null, today);
        PublicAnnouncementQuery second = PublicAnnouncementQuery.of(
                " paris ", "DAKAR", null, null, null, null, null, null, null, null, 0, today);
        assertThat(second).isEqualTo(first);

        PageResponse<PublicAnnouncementCard> a = service.search(first);
        PageResponse<PublicAnnouncementCard> b = service.search(second);

        assertThat(b).isSameAs(a);
        verify(announcementService, times(1)).searchAnnouncements(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(AnnouncementSearchExtras.PUBLIC_VIEW));
        assertThat(cacheManager.getCache(CACHE).get(first)).isNotNull();
    }

    @Test
    void desCriteresDifferentsOccupentDesEntreesDistinctes() {
        LocalDate today = LocalDate.now();
        service.search(PublicAnnouncementQuery.of(
                "Paris", "Dakar", null, null, null, null, null, null, null, null, null, today));
        service.search(PublicAnnouncementQuery.of(
                "Paris", "Abidjan", null, null, null, null, null, null, null, null, null, today));

        verify(announcementService, times(2)).searchAnnouncements(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(AnnouncementSearchExtras.PUBLIC_VIEW));
    }
}
