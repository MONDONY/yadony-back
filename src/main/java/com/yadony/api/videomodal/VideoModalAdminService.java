package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.videomodal.dto.AudienceEstimateRequest;
import com.yadony.api.videomodal.dto.AudienceEstimateResponse;
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
import java.util.ArrayList;
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
    static final int MAX_PRIORITY = 1000;
    static final int MIN_FREQUENCY_DAYS = 1;
    static final int MAX_FREQUENCY_DAYS = 365;

    private final VideoModalCampaignRepository campaignRepository;
    private final VideoModalResponseRepository responseRepository;
    private final AuditService auditService;
    private final VideoModalImageUrls imageUrls;
    private final VideoModalTargeting targeting;
    private final Clock clock;

    @Autowired
    public VideoModalAdminService(VideoModalCampaignRepository campaignRepository,
                                  VideoModalResponseRepository responseRepository,
                                  AuditService auditService,
                                  VideoModalImageUrls imageUrls,
                                  VideoModalTargeting targeting) {
        this(campaignRepository, responseRepository, auditService, imageUrls, targeting, Clock.systemUTC());
    }

    /** Sans accès au moteur d'audience : suffit pour tout sauf l'estimation. */
    VideoModalAdminService(VideoModalCampaignRepository campaignRepository,
                           VideoModalResponseRepository responseRepository,
                           AuditService auditService,
                           VideoModalImageUrls imageUrls,
                           Clock clock) {
        this(campaignRepository, responseRepository, auditService, imageUrls,
                new VideoModalTargeting(null, null, clock), clock);
    }

    VideoModalAdminService(VideoModalCampaignRepository campaignRepository,
                           VideoModalResponseRepository responseRepository,
                           AuditService auditService,
                           VideoModalImageUrls imageUrls,
                           VideoModalTargeting targeting,
                           Clock clock) {
        this.campaignRepository = campaignRepository;
        this.responseRepository = responseRepository;
        this.auditService = auditService;
        this.imageUrls = imageUrls;
        this.targeting = targeting;
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
        // Public de la v1 : celui demandé, sinon « tout le monde » pour une information et « jamais répondu »
        // pour une vidéo (défaut voulu de chaque type).
        c.setAudience(request.audience() != null ? request.audience()
                : c.getType() == VideoModalType.INFO ? VideoModalAudience.EVERYONE : VideoModalAudience.NEVER_ANSWERED);
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
        if (request.audience() != null) c.setAudience(request.audience());
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
        List<VideoModalSituation> situations = parseSituations(r.situations());
        List<VideoModalTrigger> triggers = r.triggers() == null ? List.of() : r.triggers().stream().distinct().toList();
        if (triggers.isEmpty() && situations.isEmpty()) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "video-modal-triggers-required",
                    "problem.video-modal-triggers-required");
        }
        c.setTriggers(triggers);
        c.setMaxDisplays(r.maxDisplays() == null ? DEFAULT_MAX_DISPLAYS : r.maxDisplays());
        c.setStartsAt(r.startsAt());
        c.setEndsAt(r.endsAt());
        if (type == VideoModalType.INFO) {
            applyInfo(c, r);
        } else {
            applyVideo(c, r);
        }
        applyLayout(c, r, type);
        applyTargeting(c, r, situations);
    }

    /**
     * Fréquence, priorité, public et ciblage. Tout est facultatif et retombe sur le comportement
     * d'avant (jusqu'à N fois, arrêt après une réponse, aucune condition, toutes versions et systèmes).
     */
    private void applyTargeting(VideoModalCampaignEntity c, VideoModalRequest r, List<VideoModalSituation> situations) {
        VideoModalFrequency frequency = r.frequency() == null ? VideoModalFrequency.UP_TO_N : r.frequency();
        Integer days = r.frequencyDays();
        if (days != null && (days < MIN_FREQUENCY_DAYS || days > MAX_FREQUENCY_DAYS)) {
            throw invalid("video-modal-invalid-frequency-days", "Le nombre de jours doit être compris entre "
                    + MIN_FREQUENCY_DAYS + " et " + MAX_FREQUENCY_DAYS);
        }
        if (frequency == VideoModalFrequency.EVERY_N_DAYS && days == null) {
            throw invalid("video-modal-frequency-days-required",
                    "Le nombre de jours est obligatoire pour une fréquence « tous les N jours »");
        }
        int sample = r.samplePercent() == null ? 100 : r.samplePercent();
        if (sample < 1 || sample > 100) {
            throw invalid("video-modal-invalid-sample", "Le tirage doit être compris entre 1 et 100 %");
        }
        if (r.priority() != null && (r.priority() < -MAX_PRIORITY || r.priority() > MAX_PRIORITY)) {
            throw invalid("video-modal-invalid-priority", "La priorité doit être comprise entre -" + MAX_PRIORITY
                    + " et " + MAX_PRIORITY);
        }
        String min = normalizeVersion(r.minAppVersion(), "minimale");
        String max = normalizeVersion(r.maxAppVersion(), "maximale");
        if (min != null && max != null && VideoModalAppVersions.compare(
                VideoModalAppVersions.parseStrict(min).orElseThrow(), VideoModalAppVersions.parseStrict(max).orElseThrow()) > 0) {
            throw invalid("video-modal-invalid-version-range",
                    "La version minimale ne peut pas dépasser la version maximale");
        }
        c.setFrequency(frequency);
        c.setFrequencyDays(frequency == VideoModalFrequency.EVERY_N_DAYS ? days : null);
        c.setStopAfterAnswer(r.stopAfterAnswer() == null || r.stopAfterAnswer());
        c.setAllowDontShowAgain(r.allowDontShowAgain() != null && r.allowDontShowAgain());
        c.setPriority(r.priority() == null ? 0 : r.priority());
        c.setConnectedOnly(r.connectedOnly() != null && r.connectedOnly());
        c.setConditions(targeting.normalizeConditions(r.conditions()));
        c.setMinAppVersion(min);
        c.setMaxAppVersion(max);
        c.setPlatforms(r.platforms() == null ? List.of() : r.platforms().stream().filter(java.util.Objects::nonNull).distinct().toList());
        c.setSamplePercent(sample);
        c.setSituations(situations);
        c.setTriggerOptions(parseTriggerOptions(r.triggerOptions()));
        c.setCorridors(parseCorridors(r.corridors()));
    }

    /** Seuils des moments : bornes 1..20 et 2..10, défauts 2 et 3 si absents (422 hors bornes). */
    private static VideoModalTriggerOptions parseTriggerOptions(VideoModalRequest.TriggerOptionsRequest raw) {
        int few = raw == null || raw.fewResultsMax() == null
                ? VideoModalTriggerOptions.DEFAULT_FEW_RESULTS_MAX : raw.fewResultsMax();
        if (few < VideoModalTriggerOptions.MIN_FEW_RESULTS_MAX || few > VideoModalTriggerOptions.MAX_FEW_RESULTS_MAX) {
            throw invalid("video-modal-invalid-few-results-max", "Le seuil « peu de résultats » doit être compris entre "
                    + VideoModalTriggerOptions.MIN_FEW_RESULTS_MAX + " et " + VideoModalTriggerOptions.MAX_FEW_RESULTS_MAX);
        }
        int times = raw == null || raw.tripViewedTimes() == null
                ? VideoModalTriggerOptions.DEFAULT_TRIP_VIEWED_TIMES : raw.tripViewedTimes();
        if (times < VideoModalTriggerOptions.MIN_TRIP_VIEWED_TIMES || times > VideoModalTriggerOptions.MAX_TRIP_VIEWED_TIMES) {
            throw invalid("video-modal-invalid-trip-viewed-times", "Le nombre de consultations doit être compris entre "
                    + VideoModalTriggerOptions.MIN_TRIP_VIEWED_TIMES + " et " + VideoModalTriggerOptions.MAX_TRIP_VIEWED_TIMES);
        }
        return new VideoModalTriggerOptions(few, times);
    }

    /**
     * Corridors : 10 entrées au plus, 80 caractères au plus par côté (texte libre rogné), un côté vide
     * devient nul (« n'importe lequel »). Une entrée nulle ou sans aucun côté (départ et arrivée
     * tous deux vides) est refusée en 422 ; un doublon est dédoublonné sans bruit.
     */
    private static List<VideoModalCorridor> parseCorridors(List<VideoModalRequest.CorridorRequest> raw) {
        if (raw == null) return List.of();
        if (raw.size() > VideoModalCorridor.MAX_ENTRIES) {
            throw invalid("video-modal-too-many-corridors", "Une campagne ne peut cibler que "
                    + VideoModalCorridor.MAX_ENTRIES + " corridors au plus");
        }
        List<VideoModalCorridor> out = new ArrayList<>();
        for (VideoModalRequest.CorridorRequest item : raw) {
            if (item == null) throw invalid("video-modal-invalid-corridor", "Corridor vide");
            String from = blankToNull(item.from());
            String to = blankToNull(item.to());
            if ((from != null && from.length() > VideoModalCorridor.MAX_LENGTH)
                    || (to != null && to.length() > VideoModalCorridor.MAX_LENGTH)) {
                throw invalid("video-modal-corridor-too-long", "Chaque côté d'un corridor ne peut pas dépasser "
                        + VideoModalCorridor.MAX_LENGTH + " caractères");
            }
            if (from == null && to == null) {
                throw invalid("video-modal-invalid-corridor", "Un corridor doit avoir au moins un côté (départ ou arrivée)");
            }
            VideoModalCorridor corridor = new VideoModalCorridor(from, to);
            if (!out.contains(corridor)) out.add(corridor);
        }
        return out;
    }

    private static String normalizeVersion(String raw, String which) {
        String version = blankToNull(raw);
        if (version == null) return null;
        if (VideoModalAppVersions.parseStrict(version).isEmpty()) {
            throw invalid("video-modal-invalid-version", "La version " + which
                    + " doit s'écrire x.y.z (par exemple 1.0.6)");
        }
        return version;
    }

    /**
     * Situations : genre connu, valeur dans les bornes de son unité (1 à 720 heures, 1 à 365 jours),
     * valeur par défaut si absente, ignorée pour les genres sans valeur. Un genre répété avec la même
     * valeur est dédoublonné ; avec deux valeurs différentes, refusé (une situation ne se dit qu'une fois).
     */
    private static List<VideoModalSituation> parseSituations(List<VideoModalRequest.SituationRequest> raw) {
        if (raw == null) return List.of();
        Map<VideoModalSituationKind, VideoModalSituation> byKind = new java.util.LinkedHashMap<>();
        for (VideoModalRequest.SituationRequest item : raw) {
            if (item == null) throw invalid("video-modal-invalid-situation", "Situation vide");
            VideoModalSituationKind kind = parseEnum(VideoModalSituationKind.class, item.kind());
            if (kind == null) {
                throw invalid("video-modal-invalid-situation", "Situation inconnue : « " + item.kind() + " »");
            }
            Integer value = null;
            if (kind.unit() != VideoModalSituationKind.Unit.NONE) {
                value = item.value() == null ? kind.defaultValue() : item.value();
                if (value < 1 || value > kind.maxValue()) {
                    throw invalid("video-modal-invalid-situation-value", "La valeur de la situation " + kind.name()
                            + " doit être comprise entre 1 et " + kind.maxValue()
                            + (kind.unit() == VideoModalSituationKind.Unit.HOURS ? " heures" : " jours"));
                }
            }
            VideoModalSituation situation = new VideoModalSituation(kind, value);
            VideoModalSituation previous = byKind.putIfAbsent(kind, situation);
            if (previous != null && !previous.equals(situation)) {
                throw invalid("video-modal-duplicate-situation", "La situation " + kind.name()
                        + " est indiquée deux fois avec des valeurs différentes");
            }
        }
        return new ArrayList<>(byKind.values());
    }

    /**
     * Combien de comptes le ciblage touche, avant d'enregistrer la campagne. Les mêmes contrôles que
     * la création s'appliquent (conditions du moteur de la Diffusion, versions, tirage : 422). Lecture
     * seule, aucune écriture, donc aucune entrée d'audit.
     */
    @Transactional(readOnly = true)
    public AudienceEstimateResponse estimateAudience(AudienceEstimateRequest request) {
        int sample = request.samplePercent() == null ? 100 : request.samplePercent();
        if (sample < 1 || sample > 100) {
            throw invalid("video-modal-invalid-sample", "Le tirage doit être compris entre 1 et 100 %");
        }
        normalizeVersion(request.minAppVersion(), "minimale");
        normalizeVersion(request.maxAppVersion(), "maximale");
        String conditions = targeting.normalizeConditions(request.conditions());
        VideoModalTargeting.Estimate estimate = targeting.estimate(conditions,
                request.platforms() == null ? List.of() : request.platforms().stream().filter(java.util.Objects::nonNull).distinct().toList(),
                sample);
        return new AudienceEstimateResponse(estimate.accounts(), estimate.afterSample());
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
        return YoutubeUrls.videoId(url).orElseThrow(() -> YadonyBusinessException.localized(
                HttpStatus.UNPROCESSABLE_ENTITY, "video-modal-invalid-youtube-url",
                "problem.video-modal-invalid-youtube-url"));
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
                    toLong(row[1]), toLong(row[2]), toLong(row[3]), toLong(row[4]), row.length > 5 ? toLong(row[5]) : 0L));
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
        payload.put("frequency", c.getFrequency().name());
        payload.put("frequencyDays", c.getFrequencyDays());
        payload.put("stopAfterAnswer", c.isStopAfterAnswer());
        payload.put("allowDontShowAgain", c.isAllowDontShowAgain());
        payload.put("priority", c.getPriority());
        payload.put("connectedOnly", c.isConnectedOnly());
        payload.put("samplePercent", c.getSamplePercent());
        payload.put("minAppVersion", c.getMinAppVersion());
        payload.put("maxAppVersion", c.getMaxAppVersion());
        payload.put("platforms", c.getPlatforms().stream().map(Enum::name).toList());
        payload.put("triggerOptions", Map.of("fewResultsMax", c.getTriggerOptions().fewResultsMax(),
                "tripViewedTimes", c.getTriggerOptions().tripViewedTimes()));
        payload.put("corridors", c.getCorridors().stream()
                .map(x -> (x.from() == null ? "*" : x.from()) + ">" + (x.to() == null ? "*" : x.to())).toList());
        payload.put("situations", c.getSituations().stream()
                .map(x -> x.value() == null ? x.kind().name() : x.kind().name() + ":" + x.value()).toList());
        // Empreinte de la règle, jamais ses valeurs (même convention que la Diffusion).
        AudienceRule.Group rule = c.hasConditions()
                ? AudienceRule.parse(VideoModalTargeting.readConditions(c.getConditions())) : null;
        payload.put("ruleHash", rule == null ? null : rule.hash());
        payload.put("conditionCount", rule == null ? 0 : rule.conditionCount());
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
                c.getFrequency(), c.getFrequencyDays(), c.isStopAfterAnswer(), c.isAllowDontShowAgain(),
                c.getPriority(), c.isConnectedOnly(),
                c.hasConditions() ? VideoModalTargeting.readConditions(c.getConditions()) : null,
                c.getMinAppVersion(), c.getMaxAppVersion(), c.getPlatforms(), c.getSamplePercent(),
                c.getSituations(), c.getTriggerOptions(), c.getCorridors(),
                stats == null ? VideoModalAdminResponse.Stats.EMPTY : stats);
    }

    private static Instant utc(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
