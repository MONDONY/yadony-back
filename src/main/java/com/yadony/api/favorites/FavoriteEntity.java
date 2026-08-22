package com.yadony.api.favorites;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "favorites")
@Where(clause = "deleted_at IS NULL")
public class FavoriteEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private FavoriteTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    protected FavoriteEntity() {}

    public FavoriteEntity(UUID userId, FavoriteTargetType targetType, UUID targetId) {
        this.userId = userId;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    /**
     * Réassigne ce favori à un autre utilisateur.
     *
     * <p>Seul cas d'usage : la réclamation des données d'une session visiteur
     * ({@code com.yadony.api.auth.GuestClaimService}), quand l'inscription échoue parce que
     * le numéro appartient déjà à un compte. Réassigner plutôt que supprimer/recréer
     * conserve {@code created_at}, donc l'ordre d'affichage des favoris.
     *
     * <p><b>L'appelant DOIT d'abord vérifier</b> qu'aucun favori actif du destinataire ne
     * vise déjà la même cible : l'index unique partiel {@code ux_favorites_active
     * (user_id, target_type, target_id) WHERE deleted_at IS NULL} (migration
     * {@code V152__favorites.sql}) refuserait l'UPDATE, et un seul doublon ferait échouer
     * tout le transfert.
     */
    public void reassignTo(UUID newUserId) { this.userId = newUserId; }

    public UUID getUserId() { return userId; }
    public FavoriteTargetType getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
}
