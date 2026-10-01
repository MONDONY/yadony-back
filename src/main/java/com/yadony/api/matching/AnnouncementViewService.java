package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AnnouncementInsightsResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Combien de personnes ont vu un trajet. Séparé d'{@link AnnouncementService}, dont le
 * constructeur est déjà partagé par de nombreux tests.
 */
@Service
public class AnnouncementViewService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementViewRepository viewRepository;

    public AnnouncementViewService(AnnouncementRepository announcementRepository,
                                   AnnouncementViewRepository viewRepository) {
        this.announcementRepository = announcementRepository;
        this.viewRepository = viewRepository;
    }

    /**
     * Compte une personne de plus, une seule fois : un appelant connecté, autre que le
     * voyageur, sur un trajet en ligne. Volontairement hors transaction : la contrainte
     * unique tranche entre deux ouvertures simultanées, et l'échec du second insert ne
     * doit pas marquer une transaction englobante en rollback-only.
     */
    public void recordView(UUID viewerId, UUID announcementId) {
        if (viewerId == null) {
            return;
        }
        AnnouncementEntity announcement = requireAnnouncement(announcementId);
        if (viewerId.equals(announcement.getTravelerId()) || !announcement.isPubliclyListable()) {
            return;
        }
        if (viewRepository.existsByAnnouncementIdAndViewerId(announcementId, viewerId)) {
            return;
        }
        try {
            viewRepository.save(new AnnouncementViewEntity(announcementId, viewerId));
        } catch (DataIntegrityViolationException alreadyCounted) {
            // Une ouverture simultanée a inséré la même ligne : la personne est comptée.
        }
    }

    @Transactional(readOnly = true)
    public AnnouncementInsightsResponse getInsights(UUID callerId, UUID announcementId) {
        AnnouncementEntity announcement = requireAnnouncement(announcementId);
        if (!announcement.getTravelerId().equals(callerId)) {
            // 404 et non 403 : ne pas révéler l'existence d'un trajet à qui n'en est pas le voyageur.
            throw notFound();
        }
        return new AnnouncementInsightsResponse(
                viewRepository.countByAnnouncementId(announcementId),
                announcement.getShareViewCount());
    }

    private AnnouncementEntity requireAnnouncement(UUID announcementId) {
        return announcementRepository.findById(announcementId).orElseThrow(AnnouncementViewService::notFound);
    }

    private static YadonyBusinessException notFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                "Announcement Not Found", "Annonce introuvable");
    }
}
