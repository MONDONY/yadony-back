package com.yadony.api.videomodal;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.util.UUID;

/**
 * Réponse d'un utilisateur à une version d'une campagne (table {@code video_modal_responses}).
 * Une seule ligne par campagne, utilisateur et version.
 */
@Entity
@Table(name = "video_modal_responses",
        uniqueConstraints = @UniqueConstraint(name = "uq_video_modal_responses_campaign_user_version",
                columnNames = {"campaign_id", "user_id", "version"}))
@Where(clause = "deleted_at IS NULL")
public class VideoModalResponseEntity extends BaseEntity {

    @Column(name = "campaign_id", nullable = false)
    private UUID campaignId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "displays", nullable = false)
    private int displays;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VideoModalEvent status = VideoModalEvent.SHOWN;

    @Column(name = "last_shown_at")
    private Instant lastShownAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** « Ne plus afficher » : réponse définitive pour cette version (V374), sans toucher au statut. */
    @Column(name = "dont_show_again_at")
    private Instant dontShowAgainAt;

    protected VideoModalResponseEntity() {
        // Hibernate
    }

    public VideoModalResponseEntity(UUID campaignId, UUID userId, int version) {
        this.campaignId = campaignId;
        this.userId = userId;
        this.version = version;
    }

    /**
     * Applique un événement. SHOWN incrémente le compteur ; une réponse ne fait que monter le
     * statut (jamais rétrogradé) et ne remplace pas une date déjà posée : rejouer un
     * OPENED/COMPLETED/DISMISSED ne change rien.
     */
    public void apply(VideoModalEvent event, Instant at) {
        if (event == VideoModalEvent.DONT_SHOW_AGAIN) {
            // Drapeau à part : ni statut, ni compteur d'affichages ; rejouer ne change rien.
            if (dontShowAgainAt == null) dontShowAgainAt = at;
            return;
        }
        if (event == VideoModalEvent.SHOWN) {
            displays++;
            if (lastShownAt == null || at.isAfter(lastShownAt)) lastShownAt = at;
            return;
        }
        if (answeredAt == null) answeredAt = at;
        if (event.isWatched() && openedAt == null) openedAt = at;
        if (event == VideoModalEvent.COMPLETED && completedAt == null) completedAt = at;
        if (event.rank() > status.rank()) status = event;
    }

    public UUID getCampaignId() { return campaignId; }
    public UUID getUserId() { return userId; }
    public int getVersion() { return version; }
    public int getDisplays() { return displays; }
    public VideoModalEvent getStatus() { return status; }
    public Instant getLastShownAt() { return lastShownAt; }
    public Instant getAnsweredAt() { return answeredAt; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getDontShowAgainAt() { return dontShowAgainAt; }
    public boolean isDontShowAgain() { return dontShowAgainAt != null; }
}
