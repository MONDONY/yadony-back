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

    /** Vrai si la campagne peut s'afficher à cet instant (active et dans sa période). */
    public boolean isLiveAt(Instant now) {
        return active
                && (startsAt == null || !startsAt.isAfter(now))
                && (endsAt == null || endsAt.isAfter(now));
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
