package com.yadony.api.matching;

import com.yadony.api.matching.dto.TripDelayInfo;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lecture du dernier retard d'un trajet pour les réponses d'API ({@code latestTripDelay} de
 * {@code BidResponse} et {@code AnnouncementResponse}). Une lecture unitaire pour un trajet seul,
 * une requête groupée pour une liste (pas de requête par ligne).
 */
final class TripDelays {

    private TripDelays() {}

    /** Dernier retard du trajet, ou null s'il n'en a jamais annoncé. */
    static TripDelayInfo latest(TripDelayRepository repository, UUID announcementId) {
        if (repository == null || announcementId == null) {
            return null;
        }
        return repository.findFirstByAnnouncementIdOrderByCreatedAtDesc(announcementId)
                .map(TripDelayInfo::from)
                .orElse(null);
    }

    /** Dernier retard de chacun des trajets donnés (requête bornée à un retard par trajet) ; un trajet sans
     * retard est absent de la table. */
    static Map<UUID, TripDelayInfo> latestByAnnouncement(TripDelayRepository repository,
                                                         Collection<UUID> announcementIds) {
        Map<UUID, TripDelayInfo> out = new HashMap<>();
        if (repository == null || announcementIds.isEmpty()) {
            return out;
        }
        List<UUID> distinct = announcementIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (int from = 0; from < distinct.size(); from += BidResponsePrefetch.CHUNK_SIZE) {
            List<UUID> chunk = distinct.subList(from, Math.min(from + BidResponsePrefetch.CHUNK_SIZE, distinct.size()));
            for (TripDelayEntity delay : repository.findLatestByAnnouncementIdIn(chunk)) {
                out.putIfAbsent(delay.getAnnouncementId(), TripDelayInfo.from(delay));
            }
        }
        return out;
    }
}
