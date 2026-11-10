package com.yadony.api.videomodal;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import com.yadony.api.videomodal.dto.VideoModalEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
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

    /** Jour calendaire du plafond journalier : celui de la plupart des utilisateurs (Paris). */
    static final ZoneId DAILY_CAP_ZONE = ZoneId.of("Europe/Paris");

    private final VideoModalCampaignRepository campaignRepository;
    private final VideoModalResponseRepository responseRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final VideoModalImageUrls imageUrls;
    private final VideoModalTargeting targeting;
    private final int dailyCap;
    private final Clock clock;

    @Autowired
    public VideoModalService(VideoModalCampaignRepository campaignRepository,
                             VideoModalResponseRepository responseRepository,
                             UserRepository userRepository,
                             PlatformTransactionManager transactionManager,
                             VideoModalImageUrls imageUrls,
                             VideoModalTargeting targeting,
                             @Value("${yadony.video-modal.daily-cap:1}") int dailyCap) {
        this(campaignRepository, responseRepository, userRepository,
                new TransactionTemplate(transactionManager), imageUrls, targeting, dailyCap, Clock.systemUTC());
    }

    /** Sans ciblage ni plafond journalier : le service d'avant l'étape « moment, ciblage, fréquence ». */
    VideoModalService(VideoModalCampaignRepository campaignRepository,
                      VideoModalResponseRepository responseRepository,
                      UserRepository userRepository,
                      TransactionTemplate transactionTemplate,
                      VideoModalImageUrls imageUrls,
                      Clock clock) {
        this(campaignRepository, responseRepository, userRepository, transactionTemplate, imageUrls,
                new VideoModalTargeting(null, null, clock), 0, clock);
    }

    VideoModalService(VideoModalCampaignRepository campaignRepository,
                      VideoModalResponseRepository responseRepository,
                      UserRepository userRepository,
                      TransactionTemplate transactionTemplate,
                      VideoModalImageUrls imageUrls,
                      VideoModalTargeting targeting,
                      int dailyCap,
                      Clock clock) {
        this.campaignRepository = campaignRepository;
        this.responseRepository = responseRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
        this.imageUrls = imageUrls;
        this.targeting = targeting;
        this.dailyCap = dailyCap;
        this.clock = clock;
    }

    /** Capacité envoyée par la nouvelle app : elle sait rendre les formats et les images. */
    static final String CAP_LAYOUT = "layout";

    /** Capacité envoyée par la nouvelle app : elle gère les fréquences, « ne plus afficher » et le plafond local. */
    static final String CAP_TARGETING = "targeting";

    /** Équivaut à {@code eligible(firebaseUid, lang, null)} : une app sans capacité déclarée. */
    @Transactional(readOnly = true)
    public List<VideoModalEligibleResponse> eligible(String firebaseUid, String lang) {
        return eligible(firebaseUid, lang, null);
    }

    /** Équivaut à {@code eligible(firebaseUid, lang, caps, null, null)} : version et système inconnus. */
    @Transactional(readOnly = true)
    public List<VideoModalEligibleResponse> eligible(String firebaseUid, String lang, String caps) {
        return eligible(firebaseUid, lang, caps, null, null);
    }

    /** Vrai si la liste séparée par des virgules ({@code caps=layout,…}) contient {@code layout}. */
    static boolean supportsLayout(String caps) {
        return hasCap(caps, CAP_LAYOUT);
    }

    /** Vrai si la liste contient {@code targeting}. */
    static boolean supportsTargeting(String caps) {
        return hasCap(caps, CAP_TARGETING);
    }

    private static boolean hasCap(String caps, String wanted) {
        if (caps == null) return false;
        for (String cap : caps.split(",")) {
            if (wanted.equalsIgnoreCase(cap.trim())) return true;
        }
        return false;
    }

    /**
     * @param firebaseUid uid d'un vrai compte, ou {@code null} pour un visiteur
     * @param lang        {@code en} pour l'anglais, tout le reste donne le français
     * @param caps        capacités de l'app (liste séparée par des virgules). Sans {@code layout}
     *                    (apps déjà installées), les campagnes dont le format n'est pas la feuille
     *                    du bas ou qui ont une image ne sont pas envoyées : elles seraient rendues
     *                    de travers. Sans {@code targeting}, celles à fréquence « à chaque ouverture »
     *                    ou « tous les N jours » ou avec la case « Ne plus afficher » ne le sont pas
     *                    non plus. Les campagnes ordinaires restent envoyées à tous.
     * @param appVersion  version de l'app ({@code 1.0.6}), nulle si inconnue
     * @param platform    {@code android} ou {@code ios}, nul si inconnu
     */
    @Transactional(readOnly = true)
    public List<VideoModalEligibleResponse> eligible(String firebaseUid, String lang, String caps,
                                                     String appVersion, String platform) {
        boolean layoutCapable = supportsLayout(caps);
        boolean targetingCapable = supportsTargeting(caps);
        boolean english = lang != null && lang.trim().toLowerCase().startsWith("en");
        Instant now = clock.instant();
        int[] version = VideoModalAppVersions.parseLenient(appVersion).orElse(null);
        VideoModalPlatform device = VideoModalPlatform.fromParam(platform);
        List<VideoModalCampaignEntity> live = campaignRepository.findActiveOrderByVersionPublishedAtDesc().stream()
                .filter(c -> c.isLiveAt(now))
                .filter(c -> layoutCapable || !c.needsLayoutCapability())
                .filter(c -> targetingCapable || !c.needsTargetingCapability())
                .filter(c -> VideoModalEligibility.matchesClient(c, version, device))
                .toList();
        if (live.isEmpty()) return List.of();

        UUID userId = resolveUserId(firebaseUid);
        if (userId == null) {
            // Visiteur : le ciblage fin suppose un compte, il n'a que les campagnes sans condition.
            return live.stream()
                    .filter(c -> !c.needsAccount())
                    .sorted(BY_PRIORITY)
                    .map(c -> VideoModalTexts.toEligible(c, english, 0, imageUrls))
                    .toList();
        }

        // Plafond journalier global, tous types confondus : déjà un modal aujourd'hui, plus rien.
        if (dailyCap > 0 && responseRepository.countShownSince(userId, startOfDay(now)) >= dailyCap) {
            return List.of();
        }

        Map<UUID, List<VideoModalResponseEntity>> byCampaign = responseRepository
                .findByUserIdAndCampaignIdIn(userId, live.stream().map(VideoModalCampaignEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(VideoModalResponseEntity::getCampaignId));

        Map<VideoModalSituation, Boolean> situationMemo = new HashMap<>();
        return live.stream()
                .filter(c -> VideoModalTargeting.inSample(userId, c.getId(), c.getSamplePercent()))
                .map(c -> {
                    List<VideoModalResponseEntity> responses = byCampaign.getOrDefault(c.getId(), List.of());
                    Integer displays = VideoModalEligibility.displaysIfEligible(c, responses, now);
                    if (displays == null || !targetingHolds(c, userId, situationMemo)) return null;
                    Instant lastShownAt = VideoModalEligibility.current(c, responses)
                            .map(VideoModalResponseEntity::getLastShownAt).orElse(null);
                    return new Ranked(c, VideoModalTexts.toEligible(c, english, displays, lastShownAt, imageUrls));
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt((Ranked r) -> r.campaign().getPriority()).reversed())
                .map(Ranked::response)
                .toList();
    }

    private static final Comparator<VideoModalCampaignEntity> BY_PRIORITY =
            Comparator.comparingInt(VideoModalCampaignEntity::getPriority).reversed();

    private record Ranked(VideoModalCampaignEntity campaign, VideoModalEligibleResponse response) {}

    /** Conditions de la Diffusion puis situations du serveur : les deux requêtes en dernier, elles coûtent. */
    private boolean targetingHolds(VideoModalCampaignEntity c, UUID userId, Map<VideoModalSituation, Boolean> memo) {
        if (c.hasConditions() && !targeting.matchesConditions(c.getConditions(), userId)) return false;
        for (VideoModalSituation situation : c.getSituations()) {
            if (!memo.computeIfAbsent(situation, s -> targeting.holds(s, userId))) return false;
        }
        return true;
    }

    /** Début du jour calendaire (Europe/Paris) de {@code now}, en instant. */
    private static Instant startOfDay(Instant now) {
        return now.atZone(DAILY_CAP_ZONE).toLocalDate().atStartOfDay(DAILY_CAP_ZONE).toInstant();
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
        // « Vue en entier » n'existe pas pour un INFO : accepté, jamais compté.
        if (campaign.getType() == VideoModalType.INFO && request.event() == VideoModalEvent.COMPLETED) return;
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
