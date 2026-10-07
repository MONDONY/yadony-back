package com.yadony.api.promo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromoRedemptionRepository extends JpaRepository<PromoRedemptionEntity, UUID> {

    /** Nombre d'utilisations ACTIVES (non libérées) de ce promo code par cet utilisateur. */
    long countByPromoCodeIdAndUserIdAndReleasedAtIsNull(UUID promoCodeId, UUID userId);

    /** Idempotence : déjà racheté (et non libéré) pour ce bid ? Lu en base à chaque appel. */
    boolean existsByPromoCodeIdAndBidIdAndReleasedAtIsNull(UUID promoCodeId, UUID bidId);

    Optional<PromoRedemptionEntity> findByPromoCodeIdAndBidId(UUID promoCodeId, UUID bidId);

    /** Rachats actifs d'un bid — candidats à la libération. */
    List<PromoRedemptionEntity> findByBidIdAndReleasedAtIsNull(UUID bidId);

    /**
     * Libération conditionnelle : n'écrit que si la ligne est encore active. Renvoie 1 pour
     * l'appel qui libère, 0 pour tout appel concurrent ou rejoué — c'est ce retour, et non une
     * lecture préalable, qui décide du décrément de {@code redeemed_count}.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PromoRedemptionEntity r SET r.releasedAt = :at, r.releaseReason = :reason "
            + "WHERE r.id = :id AND r.releasedAt IS NULL")
    int markReleasedIfActive(@Param("id") UUID id, @Param("at") OffsetDateTime at,
                             @Param("reason") String reason);

    /**
     * Rail de paiement d'un bid ({@code bids.payment_method}), lu directement : le paquet
     * {@code promo} n'injecte aucun service ni repository d'un autre paquet. Vide si le bid
     * n'existe pas.
     */
    @Query(value = "SELECT b.payment_method FROM bids b WHERE b.id = :bidId", nativeQuery = true)
    Optional<String> findBidPaymentMethod(@Param("bidId") UUID bidId);

    /** Bid(s) matérialisé(s) depuis un fil de négociation ({@code bids.linked_negotiation_thread_id}). */
    @Query(value = "SELECT b.id FROM bids b WHERE b.linked_negotiation_thread_id = :threadId", nativeQuery = true)
    List<UUID> findBidIdsByNegotiationThreadId(@Param("threadId") UUID threadId);
}
