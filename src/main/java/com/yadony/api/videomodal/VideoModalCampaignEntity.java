package com.yadony.api.videomodal;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Une campagne de modal vidéo (table {@code video_modal_campaigns}, V366). */
@Entity
@Table(name = "video_modal_campaigns")
@Where(clause = "deleted_at IS NULL")
public class VideoModalCampaignEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private VideoModalType type = VideoModalType.VIDEO;

    @Column(name = "body_fr", length = 500)
    private String bodyFr;

    @Column(name = "body_en", length = 500)
    private String bodyEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "icon", length = 20)
    private VideoModalIcon icon;

    @Convert(converter = VideoModalJsonConverters.Action.class)
    @Column(name = "primary_action", columnDefinition = "TEXT")
    private VideoModalAction primaryAction;

    @Convert(converter = VideoModalJsonConverters.Action.class)
    @Column(name = "secondary_action", columnDefinition = "TEXT")
    private VideoModalAction secondaryAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "layout", nullable = false, length = 20)
    private VideoModalLayout layout = VideoModalLayout.BOTTOM_SHEET;

    /** Hauteur maximale en % de l'écran (30 à 100), null = taille automatique. */
    @Column(name = "size_percent")
    private Integer sizePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_mode", nullable = false, length = 10)
    private VideoModalImageMode imageMode = VideoModalImageMode.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "image_fit", nullable = false, length = 10)
    private VideoModalImageFit imageFit = VideoModalImageFit.COVER;

    /** Clé R2 de l'image envoyée (jamais une URL). */
    @Column(name = "image_key", length = 500)
    private String imageKey;

    @Column(name = "image_alt_fr", length = 200)
    private String imageAltFr;

    @Column(name = "image_alt_en", length = 200)
    private String imageAltEn;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "youtube_url", length = 500)
    private String youtubeUrl;

    @Column(name = "youtube_video_id", length = 11)
    private String youtubeVideoId;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "title_fr", nullable = false, length = 120)
    private String titleFr;

    @Column(name = "title_en", length = 120)
    private String titleEn;

    @Column(name = "subtitle_fr", length = 300)
    private String subtitleFr;

    @Column(name = "subtitle_en", length = 300)
    private String subtitleEn;

    @Column(name = "primary_label_fr", length = 60)
    private String primaryLabelFr;

    @Column(name = "primary_label_en", length = 60)
    private String primaryLabelEn;

    @Column(name = "secondary_label_fr", length = 60)
    private String secondaryLabelFr;

    @Column(name = "secondary_label_en", length = 60)
    private String secondaryLabelEn;

    @Convert(converter = VideoModalJsonConverters.Highlights.class)
    @Column(name = "highlights", nullable = false, columnDefinition = "TEXT")
    private List<VideoModalHighlight> highlights = new ArrayList<>();

    @Convert(converter = VideoModalJsonConverters.Triggers.class)
    @Column(name = "triggers", nullable = false, columnDefinition = "TEXT")
    private List<VideoModalTrigger> triggers = new ArrayList<>();

    @Column(name = "max_displays", nullable = false)
    private int maxDisplays = 3;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 30)
    private VideoModalAudience audience = VideoModalAudience.NEVER_ANSWERED;

    @Column(name = "version_published_at", nullable = false)
    private Instant versionPublishedAt;

    // ── Moment, ciblage et fréquence (V374) ────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private VideoModalFrequency frequency = VideoModalFrequency.UP_TO_N;

    /** Obligatoire (1 à 365) pour {@code EVERY_N_DAYS}, nul sinon. */
    @Column(name = "frequency_days")
    private Integer frequencyDays;

    /** Après OPENED, COMPLETED ou DISMISSED de la version courante, ne plus afficher. */
    @Column(name = "stop_after_answer", nullable = false)
    private boolean stopAfterAnswer = true;

    @Column(name = "allow_dont_show_again", nullable = false)
    private boolean allowDontShowAgain = false;

    @Column(name = "priority", nullable = false)
    private int priority = 0;

    @Column(name = "connected_only", nullable = false)
    private boolean connectedOnly = false;

    /** JSON canonique d'un {@code AudienceRule.Group} de la Diffusion ; nul = aucune condition. */
    @Column(name = "conditions", columnDefinition = "TEXT")
    private String conditions;

    @Column(name = "min_app_version", length = 20)
    private String minAppVersion;

    @Column(name = "max_app_version", length = 20)
    private String maxAppVersion;

    /** Vide = toutes les plateformes. */
    @Convert(converter = VideoModalJsonConverters.Platforms.class)
    @Column(name = "platforms", nullable = false, columnDefinition = "TEXT")
    private List<VideoModalPlatform> platforms = new ArrayList<>();

    /** Tirage stable par personne, 1 à 100 (100 = tout le monde). */
    @Column(name = "sample_percent", nullable = false)
    private int samplePercent = 100;

    @Convert(converter = VideoModalJsonConverters.Situations.class)
    @Column(name = "situations", nullable = false, columnDefinition = "TEXT")
    private List<VideoModalSituation> situations = new ArrayList<>();

    /** Condition(s) de ciblage fin : jamais envoyée à un visiteur sans compte. */
    public boolean needsAccount() {
        return connectedOnly || hasConditions() || !situations.isEmpty() || samplePercent < 100;
    }

    public boolean hasConditions() {
        return conditions != null && !conditions.isBlank();
    }

    /**
     * Vrai si l'app doit connaître {@code caps=targeting} : fréquence « à chaque ouverture » ou
     * « tous les N jours », ou case « Ne plus afficher ». Les apps déjà installées ne la reçoivent pas.
     */
    public boolean needsTargetingCapability() {
        return frequency.needsTargetingCapability() || allowDontShowAgain;
    }

    /** Nombre d'affichages maximal de la version, ou {@code null} sans plafond (EVERY_OPEN, EVERY_N_DAYS). */
    public Integer displayCap() {
        return switch (frequency) {
            case ONCE -> 1;
            case UP_TO_N -> maxDisplays;
            case EVERY_OPEN, EVERY_N_DAYS -> null;
        };
    }

    /** Triggers servis à l'app : {@code APP_OPEN} pour une campagne à situations sans déclencheur. */
    public List<VideoModalTrigger> effectiveTriggers() {
        if (triggers.isEmpty() && !situations.isEmpty()) return List.of(VideoModalTrigger.APP_OPEN);
        return triggers;
    }

    public VideoModalFrequency getFrequency() { return frequency; }
    public void setFrequency(VideoModalFrequency frequency) { this.frequency = frequency == null ? VideoModalFrequency.UP_TO_N : frequency; }
    public Integer getFrequencyDays() { return frequencyDays; }
    public void setFrequencyDays(Integer frequencyDays) { this.frequencyDays = frequencyDays; }
    public boolean isStopAfterAnswer() { return stopAfterAnswer; }
    public void setStopAfterAnswer(boolean stopAfterAnswer) { this.stopAfterAnswer = stopAfterAnswer; }
    public boolean isAllowDontShowAgain() { return allowDontShowAgain; }
    public void setAllowDontShowAgain(boolean allowDontShowAgain) { this.allowDontShowAgain = allowDontShowAgain; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isConnectedOnly() { return connectedOnly; }
    public void setConnectedOnly(boolean connectedOnly) { this.connectedOnly = connectedOnly; }
    public String getConditions() { return conditions; }
    public void setConditions(String conditions) { this.conditions = conditions; }
    public String getMinAppVersion() { return minAppVersion; }
    public void setMinAppVersion(String minAppVersion) { this.minAppVersion = minAppVersion; }
    public String getMaxAppVersion() { return maxAppVersion; }
    public void setMaxAppVersion(String maxAppVersion) { this.maxAppVersion = maxAppVersion; }
    public List<VideoModalPlatform> getPlatforms() { return platforms; }
    public void setPlatforms(List<VideoModalPlatform> platforms) {
        this.platforms = platforms == null ? new ArrayList<>() : new ArrayList<>(platforms);
    }
    public int getSamplePercent() { return samplePercent; }
    public void setSamplePercent(int samplePercent) { this.samplePercent = samplePercent; }
    public List<VideoModalSituation> getSituations() { return situations; }
    public void setSituations(List<VideoModalSituation> situations) {
        this.situations = situations == null ? new ArrayList<>() : new ArrayList<>(situations);
    }

    /** Vrai si la campagne peut s'afficher à cet instant (active et dans sa période). */
    public boolean isLiveAt(Instant now) {
        return active
                && (startsAt == null || !startsAt.isAfter(now))
                && (endsAt == null || endsAt.isAfter(now));
    }

    public VideoModalLayout getLayout() { return layout; }
    public void setLayout(VideoModalLayout layout) { this.layout = layout == null ? VideoModalLayout.BOTTOM_SHEET : layout; }
    public Integer getSizePercent() { return sizePercent; }
    public void setSizePercent(Integer sizePercent) { this.sizePercent = sizePercent; }
    public VideoModalImageMode getImageMode() { return imageMode; }
    public void setImageMode(VideoModalImageMode imageMode) { this.imageMode = imageMode == null ? VideoModalImageMode.NONE : imageMode; }
    public VideoModalImageFit getImageFit() { return imageFit; }
    public void setImageFit(VideoModalImageFit imageFit) { this.imageFit = imageFit == null ? VideoModalImageFit.COVER : imageFit; }
    public String getImageKey() { return imageKey; }
    public void setImageKey(String imageKey) { this.imageKey = imageKey; }
    public String getImageAltFr() { return imageAltFr; }
    public void setImageAltFr(String imageAltFr) { this.imageAltFr = imageAltFr; }
    public String getImageAltEn() { return imageAltEn; }
    public void setImageAltEn(String imageAltEn) { this.imageAltEn = imageAltEn; }

    /**
     * Vrai si l'app doit connaître {@code caps=layout} pour rendre ce modal : format autre que la
     * feuille du bas, ou image. Les anciennes apps ne les reçoivent pas.
     */
    public boolean needsLayoutCapability() {
        return layout != VideoModalLayout.BOTTOM_SHEET || imageMode != VideoModalImageMode.NONE;
    }

    public VideoModalType getType() { return type; }
    public void setType(VideoModalType type) { this.type = type == null ? VideoModalType.VIDEO : type; }
    public String getBodyFr() { return bodyFr; }
    public void setBodyFr(String bodyFr) { this.bodyFr = bodyFr; }
    public String getBodyEn() { return bodyEn; }
    public void setBodyEn(String bodyEn) { this.bodyEn = bodyEn; }
    public VideoModalIcon getIcon() { return icon; }
    public void setIcon(VideoModalIcon icon) { this.icon = icon; }
    public VideoModalAction getPrimaryAction() { return primaryAction; }
    public void setPrimaryAction(VideoModalAction primaryAction) { this.primaryAction = primaryAction; }
    public VideoModalAction getSecondaryAction() { return secondaryAction; }
    public void setSecondaryAction(VideoModalAction secondaryAction) { this.secondaryAction = secondaryAction; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getYoutubeUrl() { return youtubeUrl; }
    public void setYoutubeUrl(String youtubeUrl) { this.youtubeUrl = youtubeUrl; }
    public String getYoutubeVideoId() { return youtubeVideoId; }
    public void setYoutubeVideoId(String youtubeVideoId) { this.youtubeVideoId = youtubeVideoId; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public String getTitleFr() { return titleFr; }
    public void setTitleFr(String titleFr) { this.titleFr = titleFr; }
    public String getTitleEn() { return titleEn; }
    public void setTitleEn(String titleEn) { this.titleEn = titleEn; }
    public String getSubtitleFr() { return subtitleFr; }
    public void setSubtitleFr(String subtitleFr) { this.subtitleFr = subtitleFr; }
    public String getSubtitleEn() { return subtitleEn; }
    public void setSubtitleEn(String subtitleEn) { this.subtitleEn = subtitleEn; }
    public String getPrimaryLabelFr() { return primaryLabelFr; }
    public void setPrimaryLabelFr(String primaryLabelFr) { this.primaryLabelFr = primaryLabelFr; }
    public String getPrimaryLabelEn() { return primaryLabelEn; }
    public void setPrimaryLabelEn(String primaryLabelEn) { this.primaryLabelEn = primaryLabelEn; }
    public String getSecondaryLabelFr() { return secondaryLabelFr; }
    public void setSecondaryLabelFr(String secondaryLabelFr) { this.secondaryLabelFr = secondaryLabelFr; }
    public String getSecondaryLabelEn() { return secondaryLabelEn; }
    public void setSecondaryLabelEn(String secondaryLabelEn) { this.secondaryLabelEn = secondaryLabelEn; }
    public List<VideoModalHighlight> getHighlights() { return highlights; }
    public void setHighlights(List<VideoModalHighlight> highlights) {
        this.highlights = highlights == null ? new ArrayList<>() : new ArrayList<>(highlights);
    }
    public List<VideoModalTrigger> getTriggers() { return triggers; }
    public void setTriggers(List<VideoModalTrigger> triggers) {
        this.triggers = triggers == null ? new ArrayList<>() : new ArrayList<>(triggers);
    }
    public int getMaxDisplays() { return maxDisplays; }
    public void setMaxDisplays(int maxDisplays) { this.maxDisplays = maxDisplays; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public VideoModalAudience getAudience() { return audience; }
    public void setAudience(VideoModalAudience audience) { this.audience = audience; }
    public Instant getVersionPublishedAt() { return versionPublishedAt; }
    public void setVersionPublishedAt(Instant versionPublishedAt) { this.versionPublishedAt = versionPublishedAt; }
}
