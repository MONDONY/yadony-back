package com.yadony.api.matching;

import com.yadony.api.common.PageResponse;
import com.yadony.api.matching.dto.PublicAnnouncementCard;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Recherche d'annonces pour un visiteur anonyme (lecture seule).
 *
 * <p>Réutilise {@link AnnouncementService#searchAnnouncements} en lecteur anonyme : aucun viewer
 * (donc aucun masquage de comptes bloqués ni favori), ni position, ni filtre GP Pro, week-end ou
 * KYC, tri par date croissante, pages de 20, et {@link AnnouncementSearchExtras#PUBLIC_VIEW} : ni
 * bloc 2 ({@code similarAnnouncements}) ni conversions de devise, que la carte jetterait. Le
 * résultat est converti en {@link PublicAnnouncementCard}, liste blanche de champs.
 *
 * <p>Le résultat est mis en cache 60 s dans {@code public-announcements-search}. La clé est le
 * record {@link PublicAnnouncementQuery} lui-même : ses critères sont normalisés, donc deux
 * saisies équivalentes partagent l'entrée. {@code searchAnnouncements} garde son propre cache
 * ({@code announcements-search}, 5 min) en dessous.
 */
@Service
public class PublicAnnouncementSearchService {

    static final int PAGE_SIZE = 20;
    private static final String SORT_BY = "date";
    private static final String SORT_DIR = "asc";

    private final AnnouncementService announcementService;

    public PublicAnnouncementSearchService(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    /** Spec 5.5.3 : une recherche anonyme lente ne retient pas une connexion Hikari plus de 5 s. */
    @Transactional(readOnly = true, timeout = 5)
    @Cacheable(value = "public-announcements-search", key = "#q", sync = true)
    public PageResponse<PublicAnnouncementCard> search(PublicAnnouncementQuery q) {
        return PageResponse.from(announcementService.searchAnnouncements(
                q.departureCity(), q.arrivalCity(),
                q.dateFrom(), q.dateTo(),
                q.minAvailableKg(), null,
                q.maxPricePerKg(), q.minRating(),
                null, null,
                q.transportMode(), null, q.contentType(),
                null, null, null,
                SORT_BY, SORT_DIR, PageRequest.of(q.page(), PAGE_SIZE),
                null, q.urgent(), publicExtras(q))
                .map(PublicAnnouncementCard::from));
    }

    /** Vue publique plus les deux filtres de route ; sans eux, exactement {@link AnnouncementSearchExtras#PUBLIC_VIEW}. */
    private static AnnouncementSearchExtras publicExtras(PublicAnnouncementQuery q) {
        return new AnnouncementSearchExtras(null, Set.of(), false, true, q.minItemSize(), q.viaCity());
    }
}
