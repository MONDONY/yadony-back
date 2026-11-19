package com.yadony.api.likes;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * « J'aime » public d'un utilisateur sur un trajet. Les lignes sont supprimées
 * physiquement au retrait (précédent des favoris) ; {@code deleted_at} reste pour la
 * cohérence avec {@link BaseEntity}. L'unicité en production est portée par l'index
 * partiel {@code ux_trip_likes_active} (V389) ; la contrainte déclarée ici ne sert qu'au
 * schéma H2 des tests, où Flyway est désactivé.
 */
@Entity
@Table(name = "trip_likes",
        uniqueConstraints = @UniqueConstraint(name = "ux_trip_likes_user_trip", columnNames = {"user_id", "trip_id"}))
@Where(clause = "deleted_at IS NULL")
public class TripLikeEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    protected TripLikeEntity() {}

    public TripLikeEntity(UUID userId, UUID tripId) {
        this.userId = userId;
        this.tripId = tripId;
    }

    public UUID getUserId() { return userId; }
    public UUID getTripId() { return tripId; }
}
