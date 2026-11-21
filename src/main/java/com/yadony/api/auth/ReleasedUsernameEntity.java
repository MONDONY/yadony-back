package com.yadony.api.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ancien identifiant libéré par un changement, réservé pendant un délai de grâce pour empêcher
 * qu'un tiers le récupère aussitôt et se fasse passer pour l'ancien titulaire.
 *
 * <p>N'étend pas {@code BaseEntity} volontairement : la clé primaire est l'identifiant lui-même
 * (pas d'UUID), et le soft delete ne s'applique pas. Il s'agit d'une table de réservation à durée
 * de vie limitée, purgée physiquement ({@link ReleasedUsernameRepository#deleteByReleasedAtBefore})
 * une fois le délai écoulé ; il n'y a aucune donnée à conserver ni à auditer ici.
 */
@Entity
@Table(name = "released_usernames")
public class ReleasedUsernameEntity {

    @Id
    @Column(name = "username", nullable = false, length = 32)
    private String username;

    @Column(name = "released_at", nullable = false)
    private OffsetDateTime releasedAt;

    @Column(name = "former_user_id", nullable = false)
    private UUID formerUserId;

    protected ReleasedUsernameEntity() {}

    public ReleasedUsernameEntity(String username, OffsetDateTime releasedAt, UUID formerUserId) {
        this.username = username;
        this.releasedAt = releasedAt;
        this.formerUserId = formerUserId;
    }

    public String getUsername() { return username; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public UUID getFormerUserId() { return formerUserId; }
}
