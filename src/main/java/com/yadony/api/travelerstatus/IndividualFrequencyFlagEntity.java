package com.yadony.api.travelerstatus;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Particulier qui voyage comme un professionnel, à confirmer (lot 4). Les chiffres sont figés à la détection.
 * Un signalement ne bloque, ne sanctionne et ne change jamais rien sur le compte.
 */
@Entity
@Table(name = "individual_frequency_flags")
@Where(clause = "deleted_at IS NULL")
public class IndividualFrequencyFlagEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Trajets publiés dans le mois civil de la détection (brouillons, annulés et retirés exclus). */
    @Column(name = "trips_count", nullable = false)
    private int tripsCount;

    @Column(name = "window_days", nullable = false)
    private int windowDays;

    /** Seuil en vigueur à la détection (signalé au-delà, strictement). */
    @Column(name = "threshold", nullable = false)
    private int threshold;

    /** Vrai si une demande GP Pro « chaque semaine », refusée ou annulée, a été faite dans la fenêtre. */
    @Column(name = "weekly_application", nullable = false)
    private boolean weeklyApplication;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    /** La condition qui avait déclenché le signalement a cessé. */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "dismissed_at")
    private LocalDateTime dismissedAt;

    @Column(name = "dismissed_by")
    private UUID dismissedBy;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public int getTripsCount() { return tripsCount; }
    public void setTripsCount(int tripsCount) { this.tripsCount = tripsCount; }

    public int getWindowDays() { return windowDays; }
    public void setWindowDays(int windowDays) { this.windowDays = windowDays; }

    public int getThreshold() { return threshold; }
    public void setThreshold(int threshold) { this.threshold = threshold; }

    public boolean isWeeklyApplication() { return weeklyApplication; }
    public void setWeeklyApplication(boolean weeklyApplication) { this.weeklyApplication = weeklyApplication; }

    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime detectedAt) { this.detectedAt = detectedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public LocalDateTime getDismissedAt() { return dismissedAt; }
    public void setDismissedAt(LocalDateTime dismissedAt) { this.dismissedAt = dismissedAt; }

    public UUID getDismissedBy() { return dismissedBy; }
    public void setDismissedBy(UUID dismissedBy) { this.dismissedBy = dismissedBy; }
}
