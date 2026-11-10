package com.yadony.api.videomodal;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import com.yadony.api.videomodal.dto.VideoModalEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Côté app : quels modals vidéo montrer, et enregistrement des réponses.
 *
 * <p>Sans utilisateur (visiteur sans compte), toutes les campagnes actives dans leur période
 * sont renvoyées avec {@code displays = 0} : l'app applique elle-même les règles avec les
 * réponses qu'elle a mémorisées. Avec utilisateur, le filtrage est fait ici.
 */
@Service
public class VideoModalService {

    private static final Logger log = LoggerFactory.getLogger(VideoModalService.class);

    private final VideoModalCampaignRepository campaignRepository;
    private final VideoModalResponseRepository responseRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public VideoModalService(VideoModalCampaignRepository campaignRepository,
                             VideoModalResponseRepository responseRepository,
                             UserRepository userRepository,
                             PlatformTransactionManager transactionManager) {
        this(campaignRepository, responseRepository, userRepository,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    VideoModalService(VideoModalCampaignRepository campaignRepository,
                      VideoModalResponseRepository responseRepository,
                      UserRepository userRepository,
                      TransactionTemplate transactionTemplate,
                      Clock clock) {
        this.campaignRepository = campaignRepository;
        this.responseRepository = responseRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * @param firebaseUid uid d'un vrai compte, ou {@code null} pour un visiteur
     * @param lang        {@code en} pour l'anglais, tout le reste donne le français
     */
    @Transactional(readOnly = true)
    public List<VideoModalEligibleResponse> eligible(String firebaseUid, String lang) {
        boolean english = lang != null && lang.trim().toLowerCase().startsWith("en");
        Instant now = clock.instant();
        List<VideoModalCampaignEntity> live = campaignRepository.findActiveOrderByVersionPublishedAtDesc().stream()
                .filter(c -> c.isLiveAt(now))
                .toList();
        if (live.isEmpty()) return List.of();

        UUID userId = resolveUserId(firebaseUid);
        if (userId == null) {
            return live.stream().map(c -> VideoModalTexts.toEligible(c, english, 0)).toList();
        }

        Map<UUID, List<VideoModalResponseEntity>> byCampaign = responseRepository
                .findByUserIdAndCampaignIdIn(userId, live.stream().map(VideoModalCampaignEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(VideoModalResponseEntity::getCampaignId));

        return live.stream()
                .map(c -> {
                    List<VideoModalResponseEntity> responses = byCampaign.getOrDefault(c.getId(), List.of());
                    Integer displays = VideoModalEligibility.displaysIfEligible(c, responses);
                    return displays == null ? null : VideoModalTexts.toEligible(c, english, displays);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Enregistre un événement. Campagne inconnue : 404. Version autre que la version
     * courante, ou compte sans ligne utilisateur : ignoré sans erreur.
     */
    public void recordEvent(String firebaseUid, UUID campaignId, VideoModalEventRequest request) {
        VideoModalCampaignEntity campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> notFound(campaignId));
        if (request.version() != campaign.getVersion()) {
            log.debug("[VideoModal] événement {} ignoré : version {} au lieu de {} (campagne {})",
                    request.event(), request.version(), campaign.getVersion(), campaignId);
            return;
        }
        UUID userId = resolveUserId(firebaseUid);
        if (userId == null) return;

        Instant now = clock.instant();
        Instant at = request.occurredAt() == null || request.occurredAt().isAfter(now) ? now : request.occurredAt();
        try {
            apply(campaignId, userId, campaign.getVersion(), request.event(), at);
        } catch (DataIntegrityViolationException e) {
            // Deux premiers événements simultanés : l'autre a créé la ligne, on la met à jour.
            apply(campaignId, userId, campaign.getVersion(), request.event(), at);
        }
    }

    private void apply(UUID campaignId, UUID userId, int version, VideoModalEvent event, Instant at) {
        transactionTemplate.executeWithoutResult(status -> {
            VideoModalResponseEntity response = responseRepository
                    .findByCampaignIdAndUserIdAndVersion(campaignId, userId, version)
                    .orElseGet(() -> new VideoModalResponseEntity(campaignId, userId, version));
            response.apply(event, at);
            responseRepository.saveAndFlush(response);
        });
    }

    private UUID resolveUserId(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return null;
        return userRepository.findByFirebaseUid(firebaseUid).map(UserEntity::getId).orElse(null);
    }

    static YadonyBusinessException notFound(UUID campaignId) {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, "video-modal-not-found",
                "Video Modal Not Found", "Modal vidéo introuvable : " + campaignId);
    }
}
