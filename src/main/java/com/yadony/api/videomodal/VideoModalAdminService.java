package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import com.yadony.api.videomodal.dto.VideoModalVersionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Gestion des campagnes depuis l'admin. Chaque écriture laisse une entrée audit_log. */
@Service
public class VideoModalAdminService {

    static final String AUDIT_ENTITY = "VIDEO_MODAL";
    static final int DEFAULT_MAX_DISPLAYS = 3;

    private final VideoModalCampaignRepository campaignRepository;
    private final VideoModalResponseRepository responseRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public VideoModalAdminService(VideoModalCampaignRepository campaignRepository,
                                  VideoModalResponseRepository responseRepository,
                                  AuditService auditService) {
        this(campaignRepository, responseRepository, auditService, Clock.systemUTC());
    }

    VideoModalAdminService(VideoModalCampaignRepository campaignRepository,
                           VideoModalResponseRepository responseRepository,
                           AuditService auditService,
                           Clock clock) {
        this.campaignRepository = campaignRepository;
        this.responseRepository = responseRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<VideoModalAdminResponse> list() {
        List<VideoModalCampaignEntity> campaigns = campaignRepository.findAllNewestFirst();
        Map<UUID, VideoModalAdminResponse.Stats> stats = stats(campaigns.stream().map(VideoModalCampaignEntity::getId).toList());
        return campaigns.stream().map(c -> toResponse(c, stats.get(c.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public VideoModalAdminResponse get(UUID id) {
        VideoModalCampaignEntity c = find(id);
        return toResponse(c, stats(List.of(id)).get(id));
    }

    /** Création : inactive, version 1, public NEVER_ANSWERED. */
    @Transactional
    public VideoModalAdminResponse create(VideoModalRequest request, UUID adminId) {
        VideoModalCampaignEntity c = new VideoModalCampaignEntity();
        applyContent(c, request);
        c.setActive(false);
        c.setVersion(1);
        c.setAudience(VideoModalAudience.NEVER_ANSWERED);
        c.setVersionPublishedAt(clock.instant());
        VideoModalCampaignEntity saved = campaignRepository.save(c);
        audit(saved, "VIDEO_MODAL_CREATED", adminId);
        return toResponse(saved, null);
    }

    /** Modification des contenus et réglages, sans changer de version. */
    @Transactional
    public VideoModalAdminResponse update(UUID id, VideoModalRequest request, UUID adminId) {
        VideoModalCampaignEntity c = find(id);
        applyContent(c, request);
        VideoModalCampaignEntity saved = campaignRepository.save(c);
        audit(saved, "VIDEO_MODAL_UPDATED", adminId);
        return toResponse(saved, stats(List.of(id)).get(id));
    }

    /** Nouvelle version (version + 1, publiée maintenant) pour le public choisi. */
    @Transactional
    public VideoModalAdminResponse publishVersion(UUID id, VideoModalVersionRequest request, UUID adminId) {
        VideoModalCampaignEntity c = find(id);
        if (request.youtubeUrl() != null && !request.youtubeUrl().isBlank()) {
            String url = request.youtubeUrl().trim();
            String videoId = requireVideoId(url);
            c.setYoutubeUrl(url);
            c.setYoutubeVideoId(videoId);
        }
        c.setVersion(c.getVersion() + 1);
        c.setAudience(request.audience());
        c.setVersionPublishedAt(clock.instant());
        VideoModalCampaignEntity saved = campaignRepository.save(c);
        audit(saved, "VIDEO_MODAL_VERSION_PUBLISHED", adminId);
        // Aucune réponse à une version qui vient de naître.
        return toResponse(saved, null);
    }

    @Transactional
    public VideoModalAdminResponse setActive(UUID id, boolean active, UUID adminId) {
        VideoModalCampaignEntity c = find(id);
        c.setActive(active);
        VideoModalCampaignEntity saved = campaignRepository.save(c);
        audit(saved, active ? "VIDEO_MODAL_ACTIVATED" : "VIDEO_MODAL_DEACTIVATED", adminId);
        return toResponse(saved, stats(List.of(id)).get(id));
    }

    /** Suppression douce : la campagne disparaît de l'app et de l'admin. */
    @Transactional
    public void delete(UUID id, UUID adminId) {
        VideoModalCampaignEntity c = find(id);
        c.softDelete();
        campaignRepository.save(c);
        audit(c, "VIDEO_MODAL_DELETED", adminId);
    }

    private void applyContent(VideoModalCampaignEntity c, VideoModalRequest r) {
        if (r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())) {
            throw invalid("video-modal-invalid-period", "La date de fin doit être postérieure à la date de début");
        }
        String url = r.youtubeUrl().trim();
        c.setYoutubeVideoId(requireVideoId(url));
        c.setYoutubeUrl(url);
        String titleFr = r.titleFr().trim();
        c.setName(blankToNull(r.name()) == null ? titleFr : r.name().trim());
        c.setDurationSeconds(r.durationSeconds());
        c.setTitleFr(titleFr);
        c.setTitleEn(blankToNull(r.titleEn()));
        c.setSubtitleFr(blankToNull(r.subtitleFr()));
        c.setSubtitleEn(blankToNull(r.subtitleEn()));
        c.setPrimaryLabelFr(blankToNull(r.primaryLabelFr()));
        c.setPrimaryLabelEn(blankToNull(r.primaryLabelEn()));
        c.setSecondaryLabelFr(blankToNull(r.secondaryLabelFr()));
        c.setSecondaryLabelEn(blankToNull(r.secondaryLabelEn()));
        c.setHighlights(r.highlights() == null ? List.of() : r.highlights().stream()
                .map(h -> new VideoModalHighlight(h.icon(), h.textFr().trim(), blankToNull(h.textEn())))
                .toList());
        c.setTriggers(r.triggers().stream().distinct().toList());
        c.setMaxDisplays(r.maxDisplays() == null ? DEFAULT_MAX_DISPLAYS : r.maxDisplays());
        c.setStartsAt(r.startsAt());
        c.setEndsAt(r.endsAt());
    }

    private static String requireVideoId(String url) {
        return YoutubeUrls.videoId(url).orElseThrow(() -> invalid("video-modal-invalid-youtube-url",
                "Lien YouTube non reconnu (youtu.be/ID, youtube.com/watch?v=ID, /shorts/ID ou /embed/ID)"));
    }

    private static YadonyBusinessException invalid(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Validation Error", detail);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private VideoModalCampaignEntity find(UUID id) {
        return campaignRepository.findById(id).orElseThrow(() -> VideoModalService.notFound(id));
    }

    private Map<UUID, VideoModalAdminResponse.Stats> stats(List<UUID> ids) {
        Map<UUID, VideoModalAdminResponse.Stats> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Object[] row : responseRepository.currentVersionStats(ids)) {
            out.put((UUID) row[0], new VideoModalAdminResponse.Stats(
                    toLong(row[1]), toLong(row[2]), toLong(row[3]), toLong(row[4])));
        }
        return out;
    }

    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** Clés choisies hors de la liste noire PII d'AuditService (pas de « name », « label »…). */
    private void audit(VideoModalCampaignEntity c, String action, UUID adminId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", c.getTitleFr());
        payload.put("version", c.getVersion());
        payload.put("audience", c.getAudience().name());
        payload.put("active", c.isActive());
        payload.put("youtubeVideoId", c.getYoutubeVideoId());
        payload.put("triggers", c.getTriggers().stream().map(Enum::name).toList());
        payload.put("maxDisplays", c.getMaxDisplays());
        auditService.log(AUDIT_ENTITY, c.getId(), action, adminId, payload);
    }

    static VideoModalAdminResponse toResponse(VideoModalCampaignEntity c, VideoModalAdminResponse.Stats stats) {
        return new VideoModalAdminResponse(
                c.getId(), c.getName(), c.getYoutubeUrl(), c.getYoutubeVideoId(), c.getDurationSeconds(),
                c.getTitleFr(), c.getTitleEn(), c.getSubtitleFr(), c.getSubtitleEn(),
                c.getPrimaryLabelFr(), c.getPrimaryLabelEn(), c.getSecondaryLabelFr(), c.getSecondaryLabelEn(),
                c.getHighlights().stream()
                        .map(h -> new VideoModalAdminResponse.Highlight(h.icon(), h.textFr(), h.textEn()))
                        .toList(),
                c.getTriggers(), c.getMaxDisplays(), c.getStartsAt(), c.getEndsAt(),
                c.isActive(), c.getVersion(), c.getAudience(), c.getVersionPublishedAt(),
                utc(c.getCreatedAt()), utc(c.getUpdatedAt()),
                stats == null ? VideoModalAdminResponse.Stats.EMPTY : stats);
    }

    private static Instant utc(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
