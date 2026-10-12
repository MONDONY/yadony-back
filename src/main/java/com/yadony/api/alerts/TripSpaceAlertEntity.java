package com.yadony.api.alerts;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * « Me prévenir si de la place se libère » sur un trajet complet (FLUTTER-JH).
 *
 * <p>Même module que les alertes de corridor, mais pas la même table : une alerte de
 * corridor est permanente et porte sur une ligne (villes, dates, zone), elle notifie à
 * chaque nouveau trajet ; celle-ci porte sur UN trajet et se consomme à la première
 * notification ({@link #notifiedAt}). Les mélanger aurait fait apparaître ces abonnements
 * dans « Mes alertes » et dans le digest quotidien.
 */
@Entity
@Table(name = "trip_space_alerts")
@Where(clause = "deleted_at IS NULL")
public class TripSpaceAlertEntity extends BaseEntity {

    @Column(name = "announcement_id", nullable = false)
    private UUID announcementId;

    @Column(name = "subscriber_id", nullable = false)
    private UUID subscriberId;

    /** Posé à la notification : l'abonnement est alors consommé. */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    public UUID getAnnouncementId() { return announcementId; }
    public void setAnnouncementId(UUID announcementId) { this.announcementId = announcementId; }

    public UUID getSubscriberId() { return subscriberId; }
    public void setSubscriberId(UUID subscriberId) { this.subscriberId = subscriberId; }

    public LocalDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(LocalDateTime notifiedAt) { this.notifiedAt = notifiedAt; }
}
