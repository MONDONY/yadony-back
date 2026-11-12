package com.yadony.api.matching;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Un retard de traversée (bateau) : la fourchette d'arrivée avant et après, le motif et un message
 * du voyageur. L'historique survit aux retards suivants (V382).
 */
@Entity
@Table(name = "trip_delays")
@Where(clause = "deleted_at IS NULL")
public class TripDelayEntity extends BaseEntity {

    @Column(name = "announcement_id", nullable = false)
    private UUID announcementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private TripDelayReason reason;

    @Column(name = "previous_arrival_date")
    private LocalDate previousArrivalDate;

    @Column(name = "previous_arrival_date_max")
    private LocalDate previousArrivalDateMax;

    @Column(name = "new_arrival_date", nullable = false)
    private LocalDate newArrivalDate;

    @Column(name = "new_arrival_date_max", nullable = false)
    private LocalDate newArrivalDateMax;

    @Column(name = "message", length = 300)
    private String message;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }
    public TripDelayReason getReason() { return reason; }
    public void setReason(TripDelayReason reason) { this.reason = reason; }
    public LocalDate getPreviousArrivalDate() { return previousArrivalDate; }
    public void setPreviousArrivalDate(LocalDate d) { this.previousArrivalDate = d; }
    public LocalDate getPreviousArrivalDateMax() { return previousArrivalDateMax; }
    public void setPreviousArrivalDateMax(LocalDate d) { this.previousArrivalDateMax = d; }
    public LocalDate getNewArrivalDate() { return newArrivalDate; }
    public void setNewArrivalDate(LocalDate d) { this.newArrivalDate = d; }
    public LocalDate getNewArrivalDateMax() { return newArrivalDateMax; }
    public void setNewArrivalDateMax(LocalDate d) { this.newArrivalDateMax = d; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
