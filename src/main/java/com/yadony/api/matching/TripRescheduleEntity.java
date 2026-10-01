package com.yadony.api.matching;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Un report de trajet : les horaires avant et après, pour que l'expéditeur voie ce
 * qui a changé et que l'historique survive aux reports suivants (V278).
 */
@Entity
@Table(name = "trip_reschedules")
@Where(clause = "deleted_at IS NULL")
public class TripRescheduleEntity extends BaseEntity {

    @Column(name = "announcement_id", nullable = false)
    private UUID announcementId;

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private TripRescheduleReason reason;

    @Column(name = "note", length = 300)
    private String note;

    @Column(name = "previous_departure_date", nullable = false)
    private LocalDate previousDepartureDate;

    @Column(name = "previous_departure_time")
    private LocalTime previousDepartureTime;

    @Column(name = "previous_arrival_date")
    private LocalDate previousArrivalDate;

    @Column(name = "previous_arrival_time")
    private LocalTime previousArrivalTime;

    @Column(name = "previous_handover_deadline")
    private LocalDateTime previousHandoverDeadline;

    @Column(name = "new_departure_date", nullable = false)
    private LocalDate newDepartureDate;

    @Column(name = "new_departure_time")
    private LocalTime newDepartureTime;

    @Column(name = "new_arrival_date")
    private LocalDate newArrivalDate;

    @Column(name = "new_arrival_time")
    private LocalTime newArrivalTime;

    @Column(name = "new_handover_deadline")
    private LocalDateTime newHandoverDeadline;

    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }
    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }
    public TripRescheduleReason getReason() { return reason; }
    public void setReason(TripRescheduleReason reason) { this.reason = reason; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDate getPreviousDepartureDate() { return previousDepartureDate; }
    public void setPreviousDepartureDate(LocalDate d) { this.previousDepartureDate = d; }
    public LocalTime getPreviousDepartureTime() { return previousDepartureTime; }
    public void setPreviousDepartureTime(LocalTime t) { this.previousDepartureTime = t; }
    public LocalDate getPreviousArrivalDate() { return previousArrivalDate; }
    public void setPreviousArrivalDate(LocalDate d) { this.previousArrivalDate = d; }
    public LocalTime getPreviousArrivalTime() { return previousArrivalTime; }
    public void setPreviousArrivalTime(LocalTime t) { this.previousArrivalTime = t; }
    public LocalDateTime getPreviousHandoverDeadline() { return previousHandoverDeadline; }
    public void setPreviousHandoverDeadline(LocalDateTime d) { this.previousHandoverDeadline = d; }
    public LocalDate getNewDepartureDate() { return newDepartureDate; }
    public void setNewDepartureDate(LocalDate d) { this.newDepartureDate = d; }
    public LocalTime getNewDepartureTime() { return newDepartureTime; }
    public void setNewDepartureTime(LocalTime t) { this.newDepartureTime = t; }
    public LocalDate getNewArrivalDate() { return newArrivalDate; }
    public void setNewArrivalDate(LocalDate d) { this.newArrivalDate = d; }
    public LocalTime getNewArrivalTime() { return newArrivalTime; }
    public void setNewArrivalTime(LocalTime t) { this.newArrivalTime = t; }
    public LocalDateTime getNewHandoverDeadline() { return newHandoverDeadline; }
    public void setNewHandoverDeadline(LocalDateTime d) { this.newHandoverDeadline = d; }
}
