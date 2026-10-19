package com.yadony.api.payments.supplement;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidSupplementRepository extends JpaRepository<BidSupplementEntity, UUID> {

    /** Suppléments d'un colis, du plus récent au plus ancien. */
    List<BidSupplementEntity> findByBidIdOrderByCreatedAtDesc(UUID bidId);

    /** Préchargement des listes de colis ({@code BidResponsePrefetch}) : une requête par lot. */
    List<BidSupplementEntity> findByBidIdIn(Collection<UUID> bidIds);

    @Query("SELECT s.bidId FROM BidSupplementEntity s WHERE s.id = :id")
    Optional<UUID> findBidIdById(@Param("id") UUID id);

    /**
     * Supplément d'un colis, verrouillé. Toujours APRÈS le verrou du colis
     * ({@code BidRepository.lockForUpdate}) : ordre « colis puis paiement » de {@code PaymentService}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BidSupplementEntity s WHERE s.id = :id AND s.bidId = :bidId")
    Optional<BidSupplementEntity> findByIdAndBidIdForUpdate(@Param("id") UUID id, @Param("bidId") UUID bidId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BidSupplementEntity s WHERE s.bidId = :bidId AND s.status IN :statuses")
    Optional<BidSupplementEntity> findByBidIdAndStatusInForUpdate(@Param("bidId") UUID bidId,
                                                                  @Param("statuses") Collection<BidSupplementStatus> statuses);

    boolean existsByBidIdAndStatusIn(UUID bidId, Collection<BidSupplementStatus> statuses);

    long countByBidId(UUID bidId);

    /** Filet : demandes encore ouvertes dont l'échéance est passée. */
    @Query("SELECT s.id FROM BidSupplementEntity s WHERE s.status IN :statuses AND s.expiresAt < :now ORDER BY s.expiresAt")
    List<UUID> findIdsOpenExpiredBefore(@Param("statuses") Collection<BidSupplementStatus> statuses,
                                        @Param("now") LocalDateTime now, Pageable page);

    /**
     * Filet : colis morts sans livraison (statut stable depuis {@code before}) qui gardent une
     * demande dans l'un des {@code statuses}.
     */
    @Query("SELECT DISTINCT s.bidId FROM BidSupplementEntity s, com.yadony.api.matching.BidEntity b "
            + "WHERE b.id = s.bidId AND s.status IN :statuses AND b.status IN :bidStatuses AND b.updatedAt < :before")
    List<UUID> findBidIdsEndedWithSupplementIn(@Param("statuses") Collection<BidSupplementStatus> statuses,
                                               @Param("bidStatuses") Collection<com.yadony.api.matching.BidStatus> bidStatuses,
                                               @Param("before") LocalDateTime before, Pageable page);

    /** Purge après livraison : colis dont une photo de supplément n'est pas encore purgée. */
    @Query("SELECT DISTINCT s.bidId FROM BidSupplementEntity s WHERE s.photoPurgedAt IS NULL")
    List<UUID> findBidIdsWithLivePhotos();

    @Query("SELECT s FROM BidSupplementEntity s WHERE s.bidId = :bidId AND s.photoPurgedAt IS NULL")
    List<BidSupplementEntity> findLivePhotosByBidId(@Param("bidId") UUID bidId);

    /**
     * Rapprochement R2 : clés photo encore vivantes sous {@code prefix}, suppléments supprimés
     * logiquement compris ; une photo purgée n'est plus revendiquée.
     */
    @Query(value = "SELECT photo_key FROM bid_supplements WHERE photo_key LIKE CONCAT(:prefix, '%') "
            + "AND photo_purged_at IS NULL", nativeQuery = true)
    List<String> findPhotoKeysUnderIncludingDeleted(@Param("prefix") String prefix);
    // ── Rail carte (B2) ──────────────────────────────────────────────────────

    Optional<BidSupplementEntity> findByIdAndBidId(UUID id, UUID bidId);

    Optional<BidSupplementEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    Optional<BidSupplementEntity> findByStripeChargeId(String stripeChargeId);

    List<BidSupplementEntity> findByBidIdAndRailAndStatus(UUID bidId, BidSupplementRail rail, BidSupplementStatus status);

    /** Filet carte : suppléments d'un rail dans l'un des {@code statuses}, inchangés depuis {@code before}. */
    @Query("SELECT s.id FROM BidSupplementEntity s WHERE s.rail = :rail AND s.status IN :statuses "
            + "AND s.updatedAt < :before ORDER BY s.updatedAt")
    List<UUID> findIdsByRailAndStatusInUpdatedBefore(@Param("rail") BidSupplementRail rail,
                                                     @Param("statuses") Collection<BidSupplementStatus> statuses,
                                                     @Param("before") LocalDateTime before, Pageable page);

    /**
     * Filet carte : paiements tardifs (arrivés sur une demande déjà close) encore à rembourser.
     * La demande garde son statut clos : la passer en REFUND_PENDING heurterait l'index
     * {@code uq_bid_supplements_one_active} si une autre demande a été ouverte entre-temps.
     */
    @Query("SELECT s.id FROM BidSupplementEntity s WHERE s.rail = :rail AND s.status IN :statuses "
            + "AND s.paidAt IS NOT NULL AND s.refundedAt IS NULL AND s.updatedAt < :before ORDER BY s.updatedAt")
    List<UUID> findIdsPaidLateUnrefunded(@Param("rail") BidSupplementRail rail,
                                         @Param("statuses") Collection<BidSupplementStatus> statuses,
                                         @Param("before") LocalDateTime before, Pageable page);

    /**
     * Filet carte, versement manqué : suppléments {@code PAID} depuis {@code before} dont le
     * versement principal a réellement abouti par l'exécuteur des versements (paiement
     * {@code RELEASED} et réservation {@code stripe_release_intents} {@code DONE}, donc
     * {@code PaymentReleasedEvent} publié). Filtré ici et non en Java après coup : sinon plus de
     * {@code page} suppléments en transit (principal pas encore versé) cacheraient à jamais un
     * supplément réellement manqué. Paiement du colis ou du fil de négociation lié, comme
     * {@code PaymentRepository.findForBid}. Un partage admin d'un litige ne passe pas par la
     * réservation : le supplément reste payé (décision manuelle).
     */
    @Query("""
            SELECT s.id FROM BidSupplementEntity s
            WHERE s.rail = :rail
              AND s.status = com.yadony.api.payments.supplement.BidSupplementStatus.PAID
              AND s.paidAt < :before
              AND EXISTS (
                  SELECT 1 FROM com.yadony.api.payments.PaymentEntity p,
                                com.yadony.api.payments.StripeReleaseIntentEntity i,
                                com.yadony.api.matching.BidEntity b
                  WHERE b.id = s.bidId
                    AND i.paymentId = p.id
                    AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.DONE
                    AND p.status = com.yadony.api.payments.PaymentStatus.RELEASED
                    AND (p.bidId = s.bidId
                         OR (p.bidId IS NULL AND b.linkedNegotiationThreadId IS NOT NULL
                             AND p.negotiationThreadId = b.linkedNegotiationThreadId)))
            ORDER BY s.paidAt
            """)
    List<UUID> findIdsAwaitingMissedPayout(@Param("rail") BidSupplementRail rail,
                                           @Param("before") LocalDateTime before, Pageable page);

    // ── Rail mobile money (B3) ───────────────────────────────────────────────

    /**
     * Filet mobile money, versement manqué : suppléments {@code PAWAPAY} {@code PAID} depuis
     * {@code before} dont le versement principal a réellement abouti chez pawaPay (paiement
     * {@code RELEASED} et payout principal {@code COMPLETED}, donc {@code PaymentReleasedEvent}
     * publié). Filtré en base, comme le filet carte, pour qu'aucun supplément en transit ne cache un
     * supplément réellement manqué. Paiement du colis ou du fil de négociation lié.
     */
    @Query("""
            SELECT s.id FROM BidSupplementEntity s
            WHERE s.rail = com.yadony.api.payments.supplement.BidSupplementRail.PAWAPAY
              AND s.status = com.yadony.api.payments.supplement.BidSupplementStatus.PAID
              AND s.paidAt < :before
              AND EXISTS (
                  SELECT 1 FROM com.yadony.api.payments.PaymentEntity p,
                                com.yadony.api.payments.pawapay.PawapayOperationEntity o,
                                com.yadony.api.matching.BidEntity b
                  WHERE b.id = s.bidId
                    AND o.paymentId = p.id
                    AND o.kind = com.yadony.api.payments.pawapay.PawapayOperationKind.PAYOUT
                    AND o.status = com.yadony.api.payments.pawapay.PawapayOperationStatus.COMPLETED
                    AND p.status = com.yadony.api.payments.PaymentStatus.RELEASED
                    AND (p.bidId = s.bidId
                         OR (p.bidId IS NULL AND b.linkedNegotiationThreadId IS NOT NULL
                             AND p.negotiationThreadId = b.linkedNegotiationThreadId)))
            ORDER BY s.paidAt
            """)
    List<UUID> findIdsAwaitingMissedMobileMoneyPayout(@Param("before") LocalDateTime before, Pageable page);

    /**
     * Filet mobile money : dépôts aboutis chez pawaPay mais jamais appliqués au supplément (écouteur
     * en échec) : demande encore en attente de paiement, ou close sans paiement enregistré.
     */
    @Query("""
            SELECT s.id FROM BidSupplementEntity s
            WHERE s.rail = com.yadony.api.payments.supplement.BidSupplementRail.PAWAPAY
              AND s.status IN :statuses
              AND s.paidAt IS NULL
              AND s.updatedAt < :before
              AND EXISTS (
                  SELECT 1 FROM com.yadony.api.payments.pawapay.PawapayOperationEntity o
                  WHERE o.bidSupplementId = s.id
                    AND o.kind = com.yadony.api.payments.pawapay.PawapayOperationKind.DEPOSIT
                    AND o.status = com.yadony.api.payments.pawapay.PawapayOperationStatus.COMPLETED)
            ORDER BY s.updatedAt
            """)
    List<UUID> findIdsWithUnappliedMobileMoneyDeposit(@Param("statuses") Collection<BidSupplementStatus> statuses,
                                                      @Param("before") LocalDateTime before, Pageable page);
}
