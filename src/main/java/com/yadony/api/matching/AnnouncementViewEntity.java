package com.yadony.api.matching;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * Première consultation d'un trajet par une personne, autre que son voyageur.
 * Une seule ligne par couple (trajet, personne) : le nombre de lignes d'un trajet
 * est le nombre de personnes qui l'ont vu, quel que soit le nombre d'ouvertures.
 */
@Entity
@Table(name = "announcement_views",
       uniqueConstraints = @UniqueConstraint(name = "uq_announcement_views",
                                             columnNames = {"announcement_id", "viewer_id"}))
@SQLRestriction("deleted_at IS NULL")
public class AnnouncementViewEntity extends BaseEntity {

    @Column(name = "announcement_id", nullable = false, updatable = false)
    private UUID announcementId;

    @Column(name = "viewer_id", nullable = false, updatable = false)
    private UUID viewerId;

    protected AnnouncementViewEntity() {}

    public AnnouncementViewEntity(UUID announcementId, UUID viewerId) {
        this.announcementId = announcementId;
        this.viewerId = viewerId;
    }

    public UUID getAnnouncementId() { return announcementId; }
    public UUID getViewerId() { return viewerId; }
}
