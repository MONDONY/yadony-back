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
    static final int MAX_BODY = 500;
    static final int MAX_INFO_LABEL = 26;
    static final int MIN_SIZE_PERCENT = 30;
    static final int MAX_SIZE_PERCENT = 100;

    private final VideoModalCampaignRepository campaignRepository;
    private final VideoModalResponseRepository responseRepository;
    private final AuditService auditService;
    private final VideoModalImageUrls imageUrls;
    private final Clock clock;

    @Autowired
    public VideoModalAdminService(VideoModalCampaignRepository campaignRepository,
                                  VideoModalResponseRepository responseRepository,
                                  AuditService auditService,
                                  VideoModalImageUrls imageUrls) {
        this(campaignRepository, responseRepository, auditService, imageUrls, Clock.systemUTC());
    }

    VideoModalAdminService(VideoModalCampaignRepository campaignRepository,
                           VideoModalResponseRepository responseRepository,
                           AuditService auditService,
                           VideoModalImageUrls imageUrls,
                           Clock clock) {
        this.campaignRepository = campaignRepository;
        this.responseRepository = responseRepository;
        this.auditService = auditService;
        this.imageUrls = imageUrls;
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
        if (c.getType() == VideoModalType.VIDEO && request.youtubeUrl() != null && !request.youtubeUrl().isBlank()) {
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
        VideoModalType type = r.type() == null ? VideoModalType.VIDEO : r.type();
        String titleFr = r.titleFr().trim();
        c.setType(type);
        c.setName(blankToNull(r.name()) == null ? titleFr : r.name().trim());
        c.setTitleFr(titleFr);
        c.setTitleEn(blankToNull(r.titleEn()));
        c.setSubtitleFr(blankToNull(r.subtitleFr()));
        c.setSubtitleEn(blankToNull(r.subtitleEn()));
        c.setTriggers(r.triggers().stream().distinct().toList());
        c.setMaxDisplays(r.maxDisplays() == null ? DEFAULT_MAX_DISPLAYS : r.maxDisplays());
        c.setStartsAt(r.startsAt());
        c.setEndsAt(r.endsAt());
        if (type == VideoModalType.INFO) {
            applyInfo(c, r);
        } else {
            applyVideo(c, r);
        }
        applyLayout(c, r, type);
    }

    /**
     * Format, taille et image. L'image elle-même est envoyée à part ({@code POST …/image}) : ici
     * seul son usage se règle. VIDEO n'a jamais d'image (mode forcé NONE) ; un mode autre que
     * NONE exige une image déjà envoyée, donc jamais à la création.
     */
    private static void applyLayout(VideoModalCampaignEntity c, VideoModalRequest r, VideoModalType type) {
        Integer size = r.sizePercent();
        if (size != null && (size < MIN_SIZE_PERCENT || size > MAX_SIZE_PERCENT)) {
            throw invalid("video-modal-invalid-size", "La taille doit être comprise entre " + MIN_SIZE_PERCENT
                    + " et " + MAX_SIZE_PERCENT + " % de la hauteur de l'écran");
        }
        VideoModalImageMode mode = type == VideoModalType.INFO && r.imageMode() != null
                ? r.imageMode() : VideoModalImageMode.NONE;
        if (mode != VideoModalImageMode.NONE && c.getImageKey() == null) {
            throw invalid("video-modal-image-required", "Envoyez d'abord une image pour utiliser le mode "
                    + mode.name() + " (enregistrez la campagne, puis ajoutez l'image)");
        }
        c.setLayout(r.layout());
        c.setSizePercent(size);
        c.setImageMode(mode);
        c.setImageFit(r.imageFit());
        c.setImageAltFr(type == VideoModalType.INFO ? blankToNull(r.imageAltFr()) : null);
        c.setImageAltEn(type == VideoModalType.INFO ? blankToNull(r.imageAltEn()) : null);
    }

    private void applyVideo(VideoModalCampaignEntity c, VideoModalRequest r) {
        String url = r.youtubeUrl() == null ? "" : r.youtubeUrl().trim();
        c.setYoutubeVideoId(requireVideoId(url));
        c.setYoutubeUrl(url);
        c.setDurationSeconds(r.durationSeconds());
        c.setPrimaryLabelFr(blankToNull(r.primaryLabelFr()));
        c.setPrimaryLabelEn(blankToNull(r.primaryLabelEn()));
        c.setSecondaryLabelFr(blankToNull(r.secondaryLabelFr()));
        c.setSecondaryLabelEn(blankToNull(r.secondaryLabelEn()));
        c.setHighlights(r.highlights() == null ? List.of() : r.highlights().stream()
                .map(h -> new VideoModalHighlight(h.icon(), h.textFr().trim(), blankToNull(h.textEn())))
                .toList());
        c.setBodyFr(null);
        c.setBodyEn(null);
        c.setIcon(null);
        c.setPrimaryAction(null);
        c.setSecondaryAction(null);
    }

    /** Modal d'information : lien, durée et points forts sont ignorés. */
    private void applyInfo(VideoModalCampaignEntity c, VideoModalRequest r) {
        String bodyFr = blankToNull(r.bodyFr());
        if (bodyFr == null) {
            throw invalid("video-modal-body-required", "Le texte du modal (français) est obligatoire");
        }
        String bodyEn = blankToNull(r.bodyEn());
        if (bodyFr.length() > MAX_BODY || (bodyEn != null && bodyEn.length() > MAX_BODY)) {
            throw invalid("video-modal-body-too-long", "Le texte du modal ne peut pas dépasser " + MAX_BODY + " caractères");
        }
        if (r.primaryAction() == null) {
            throw invalid("video-modal-primary-action-required", "L'action du bouton principal est obligatoire");
        }
        VideoModalAction primary = toAction(r.primaryAction(), "principal");
        requireLabel(primary, r.primaryLabelFr(), r.primaryLabelEn(), "principal");
        VideoModalAction secondary = r.secondaryAction() == null ? null : toAction(r.secondaryAction(), "secondaire");
        if (secondary != null) requireLabel(secondary, r.secondaryLabelFr(), r.secondaryLabelEn(), "secondaire");

        c.setYoutubeUrl(null);
        c.setYoutubeVideoId(null);
        c.setDurationSeconds(null);
        c.setHighlights(List.of());
        c.setBodyFr(bodyFr);
        c.setBodyEn(bodyEn);
        c.setIcon(r.icon() == null ? VideoModalIcon.INFO : r.icon());
        c.setPrimaryAction(primary);
        c.setSecondaryAction(secondary);
        c.setPrimaryLabelFr(blankToNull(r.primaryLabelFr()));
        c.setPrimaryLabelEn(blankToNull(r.primaryLabelEn()));
        c.setSecondaryLabelFr(secondary == null ? null : blankToNull(r.secondaryLabelFr()));
        c.setSecondaryLabelEn(secondary == null ? null : blankToNull(r.secondaryLabelEn()));
    }

    /** Action normalisée : seul le champ utile à son type est conservé. */
    private static VideoModalAction toAction(VideoModalRequest.ActionRequest a, String button) {
        VideoModalActionKind kind = parseEnum(VideoModalActionKind.class, a.kind());
        if (kind == null) {
            throw invalid("video-modal-invalid-action", "Type d'action inconnu pour le bouton " + button
                    + " (SCREEN, URL ou CLOSE)");
        }
        return switch (kind) {
            case CLOSE -> new VideoModalAction(VideoModalActionKind.CLOSE, null, null);
            case SCREEN -> {
                InfoScreen screen = parseEnum(InfoScreen.class, a.screen());
                if (screen == null) {
                    throw invalid("video-modal-invalid-screen", "Écran inconnu ou manquant pour le bouton " + button);
                }
                yield new VideoModalAction(VideoModalActionKind.SCREEN, screen, null);
            }
            case URL -> {
                String url = ExternalUrls.requireHttps(a.url());
                if (url == null) {
                    throw invalid("video-modal-invalid-url", "Le lien du bouton " + button
                            + " doit être une adresse https valide de " + ExternalUrls.MAX_LENGTH + " caractères au plus");
                }
                yield new VideoModalAction(VideoModalActionKind.URL, null, url);
            }
        };
    }

    /** Libellé FR obligatoire pour SCREEN et URL ; 26 caractères au plus (FR et EN). */
    private static void requireLabel(VideoModalAction action, String labelFr, String labelEn, String button) {
        String fr = blankToNull(labelFr);
        String en = blankToNull(labelEn);
        if (action.kind() != VideoModalActionKind.CLOSE && fr == null) {
            throw invalid("video-modal-label-required", "Le libellé français du bouton " + button + " est obligatoire");
        }
        if ((fr != null && fr.length() > MAX_INFO_LABEL) || (en != null && en.length() > MAX_INFO_LABEL)) {
            throw invalid("video-modal-label-too-long", "Le libellé du bouton " + button
                    + " ne peut pas dépasser " + MAX_INFO_LABEL + " caractères");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        payload.put("type", c.getType().name());
        payload.put("title", c.getTitleFr());
        payload.put("version", c.getVersion());
        payload.put("audience", c.getAudience().name());
        payload.put("active", c.isActive());
        if (c.getYoutubeVideoId() != null) payload.put("youtubeVideoId", c.getYoutubeVideoId());
        payload.put("triggers", c.getTriggers().stream().map(Enum::name).toList());
        payload.put("maxDisplays", c.getMaxDisplays());
        payload.put("layout", c.getLayout().name());
        payload.put("sizePercent", c.getSizePercent());
        payload.put("imageMode", c.getImageMode().name());
        auditService.log(AUDIT_ENTITY, c.getId(), action, adminId, payload);
    }

    VideoModalAdminResponse toResponse(VideoModalCampaignEntity c, VideoModalAdminResponse.Stats stats) {
        return new VideoModalAdminResponse(
                c.getId(), c.getType(), c.getName(), c.getYoutubeUrl(), c.getYoutubeVideoId(), c.getDurationSeconds(),
                c.getTitleFr(), c.getTitleEn(), c.getSubtitleFr(), c.getSubtitleEn(),
                c.getPrimaryLabelFr(), c.getPrimaryLabelEn(), c.getSecondaryLabelFr(), c.getSecondaryLabelEn(),
                c.getBodyFr(), c.getBodyEn(), c.getIcon(), c.getPrimaryAction(), c.getSecondaryAction(),
                c.getHighlights().stream()
                        .map(h -> new VideoModalAdminResponse.Highlight(h.icon(), h.textFr(), h.textEn()))
                        .toList(),
                c.getTriggers(), c.getMaxDisplays(), c.getStartsAt(), c.getEndsAt(),
                c.isActive(), c.getVersion(), c.getAudience(), c.getVersionPublishedAt(),
                utc(c.getCreatedAt()), utc(c.getUpdatedAt()),
                c.getLayout(), c.getSizePercent(), c.getImageMode(), c.getImageFit(),
                imageUrls.urlOf(c.getImageKey()), c.getImageAltFr(), c.getImageAltEn(),
                stats == null ? VideoModalAdminResponse.Stats.EMPTY : stats);
    }

    private static Instant utc(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
