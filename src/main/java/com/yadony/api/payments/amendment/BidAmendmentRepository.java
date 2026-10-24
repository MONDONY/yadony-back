package com.yadony.api.payments.amendment;

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

public interface BidAmendmentRepository extends JpaRepository<BidAmendmentEntity, UUID> {

    /**
     * Modification d'un colis, verrouillée. Toujours APRÈS le verrou du colis
     * ({@code BidRepository.lockForUpdate}) : ordre « colis puis paiement » de {@code PaymentService}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM BidAmendmentEntity a WHERE a.id = :id AND a.bidId = :bidId")
    Optional<BidAmendmentEntity> findByIdAndBidIdForUpdate(@Param("id") UUID id, @Param("bidId") UUID bidId);

    /** Colis d'une modification, sans la charger : elle est relue verrouillée après le verrou du colis. */
    @Query("SELECT a.bidId FROM BidAmendmentEntity a WHERE a.id = :id")
    Optional<UUID> findBidIdById(@Param("id") UUID id);

    /** Modification espèces dont {@code commissionPaymentIntentId} est la PaymentIntent de commission. */
    Optional<BidAmendmentEntity> findByCommissionPaymentIntentId(String commissionPaymentIntentId);

    /** Modification carte dont {@code stripePaymentIntentId} est la PaymentIntent (montant bloqué puis débité). */
    Optional<BidAmendmentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    Optional<BidAmendmentEntity> findFirstByBidIdAndStatusIn(UUID bidId, Collection<BidAmendmentStatus> statuses);

    /** Modifications d'un colis, de la plus récente à la plus ancienne. */
    List<BidAmendmentEntity> findByBidIdOrderByCreatedAtDesc(UUID bidId);

    /** Modifications acceptées du colis (plafond {@code max-accepted-per-bid}), remboursées comprises. */
    long countByBidIdAndAcceptedAtNotNull(UUID bidId);

    /**
     * Somme des nets des modifications du colis dans l'un des {@code statuses} (0 si aucune). Liste
     * positive : une modification close après acceptation ({@code CANCELLED}, {@code EXPIRED}...) ou
     * remboursée n'est jamais comptée, même avec {@code accepted_at} renseigné.
     */
    @Query("SELECT COALESCE(SUM(a.netAmount), 0) FROM BidAmendmentEntity a "
            + "WHERE a.bidId = :bidId AND a.status IN :statuses")
    java.math.BigDecimal sumNetByBidIdAndStatusIn(@Param("bidId") UUID bidId,
                                                  @Param("statuses") Collection<BidAmendmentStatus> statuses);

    /** Préchargement des listes de colis : une requête par lot. */
    List<BidAmendmentEntity> findByBidIdIn(Collection<UUID> bidIds);

    /** Le colis a-t-il une modification dans l'un des {@code statuses} ? (supplément : une seule transaction ouverte). */
    boolean existsByBidIdAndStatusIn(UUID bidId, Collection<BidAmendmentStatus> statuses);

    /** Parmi {@code bidIds}, les colis qui ont une modification dans l'un des {@code statuses} : une requête par lot. */
    @Query("SELECT DISTINCT a.bidId FROM BidAmendmentEntity a WHERE a.bidId IN :bidIds AND a.status IN :statuses")
    List<UUID> findBidIdsByBidIdInAndStatusIn(@Param("bidIds") Collection<UUID> bidIds,
                                              @Param("statuses") Collection<BidAmendmentStatus> statuses);

    /** Modifications d'un colis, verrouillées, toujours APRÈS le verrou du colis et celui du trajet (fin du colis). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM BidAmendmentEntity a WHERE a.bidId = :bidId ORDER BY a.createdAt")
    List<BidAmendmentEntity> findByBidIdForUpdate(@Param("bidId") UUID bidId);

    // ── Filet planifié (B6) ──────────────────────────────────────────────────

    /**
     * Filet : modifications ouvertes dont l'étape en cours est échue, même règle que
     * {@link BidAmendmentWindow#openUntil} (autorisation carte, réponse, commission espèces, dépôt).
     *
     * @param authorizedBefore maintenant − {@code authorization-window}.
     * @param commissionBefore maintenant − {@code commission-confirm-window}.
     */
    @Query("""
            SELECT a.id FROM BidAmendmentEntity a
            WHERE (a.status = com.yadony.api.payments.amendment.BidAmendmentStatus.AUTHORIZATION_PENDING
                   AND a.createdAt < :authorizedBefore)
               OR (a.status = com.yadony.api.payments.amendment.BidAmendmentStatus.PROPOSED AND a.expiresAt < :now)
               OR (a.status = com.yadony.api.payments.amendment.BidAmendmentStatus.COMMISSION_PENDING
                   AND ((a.respondedAt IS NOT NULL AND a.respondedAt < :commissionBefore)
                        OR (a.respondedAt IS NULL AND a.expiresAt < :now)))
               OR (a.status = com.yadony.api.payments.amendment.BidAmendmentStatus.PAYMENT_PENDING
                   AND COALESCE(a.paymentExpiresAt, a.expiresAt) < :now)
            ORDER BY a.expiresAt
            """)
    List<UUID> findIdsLapsed(@Param("now") LocalDateTime now, @Param("authorizedBefore") LocalDateTime authorizedBefore,
                             @Param("commissionBefore") LocalDateTime commissionBefore, Pageable page);

    /**
     * Filet : modifications proposées dont l'échéance tombe dans {@code ]now, now + reminderBefore]}, sans rappel
     * envoyé, et dont la fenêtre de réponse dépasse {@code reminderBefore} ({@code created_at < expires_at −
     * reminderBefore}) : une demande plus courte n'est jamais rappelée, sa notification de demande vient de partir.
     */
    @Query(value = """
            SELECT a.id FROM bid_amendments a
            WHERE a.deleted_at IS NULL AND a.status = 'PROPOSED' AND a.reminder_sent_at IS NULL
              AND a.expires_at > CAST(:now AS TIMESTAMP)
              AND a.expires_at <= CAST(:now AS TIMESTAMP) + CAST(:reminderSeconds AS BIGINT) * INTERVAL '1 second'
              AND a.created_at < a.expires_at - CAST(:reminderSeconds AS BIGINT) * INTERVAL '1 second'
            ORDER BY a.expires_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findIdsReminderDue(@Param("now") LocalDateTime now, @Param("reminderSeconds") long reminderSeconds,
                                  @Param("limit") int limit);

    /**
     * Le colis a-t-il une modification que sa fin doit traiter : ouverte, acquise, ou des kilos encore retirés du
     * trajet ? Lecture simple, sans verrou : sortie rapide de la fin de colis.
     */
    @Query("SELECT COUNT(a) > 0 FROM BidAmendmentEntity a WHERE a.bidId = :bidId "
            + "AND (a.status IN :statuses OR a.capacityReservedKg > 0)")
    boolean existsToEnd(@Param("bidId") UUID bidId, @Param("statuses") Collection<BidAmendmentStatus> statuses);

    /**
     * Filet : colis morts sans livraison (statut stable depuis {@code before}) qui gardent une modification dans
     * l'un des {@code statuses} ou des kilos encore retirés du trajet.
     */
    @Query("""
            SELECT a.bidId FROM BidAmendmentEntity a, com.yadony.api.matching.BidEntity b
            WHERE b.id = a.bidId AND b.status IN :bidStatuses AND b.updatedAt < :before
              AND (a.status IN :statuses OR a.capacityReservedKg > 0)
            GROUP BY a.bidId, b.updatedAt
            ORDER BY b.updatedAt, a.bidId
            """)
    List<UUID> findBidIdsEndedWith(@Param("statuses") Collection<BidAmendmentStatus> statuses,
                                   @Param("bidStatuses") Collection<com.yadony.api.matching.BidStatus> bidStatuses,
                                   @Param("before") LocalDateTime before, Pageable page);

    /**
     * Filet du rail espèces : modification close dont la commission reste prélevée ({@code CHARGED}) ou dont la
     * PaymentIntent de commission n'a pas été défaite (3DS en cours), inchangée depuis {@code before} : la suite
     * après commit ({@link BidAmendmentCashListener}) a échoué ou s'est perdue.
     */
    @Query("""
            SELECT a.id FROM BidAmendmentEntity a
            WHERE a.rail = com.yadony.api.payments.supplement.BidSupplementRail.CASH
              AND a.status IN :closed AND a.updatedAt < :before
              AND (a.commissionStatus = com.yadony.api.payments.cash.CommissionStatus.CHARGED
                   OR ((a.commissionStatus IS NULL
                        OR a.commissionStatus = com.yadony.api.payments.cash.CommissionStatus.REQUIRES_3DS)
                       AND a.commissionPaymentIntentId IS NOT NULL))
            ORDER BY a.updatedAt
            """)
    List<UUID> findCashIdsToSettle(@Param("closed") Collection<BidAmendmentStatus> closed,
                                   @Param("before") LocalDateTime before, Pageable page);

    /**
     * Filet : PaymentIntents de commission abouties hors de l'essai courant (audit {@code BID_AMENDMENT_STALE_COMMISSION},
     * un par rejeu de webhook) tracées depuis {@code since}, sans Refund connu ({@code ..._REFUNDED} écrit par
     * {@code refundStale}, ou {@code ..._REFUND_CONFIRMED} par le webhook {@code charge.refunded}). Une ligne par
     * PaymentIntent : {@code [amendmentId, bidId, piId]}, en texte. Une PaymentIntent sort du balayage quand ses
     * échecs de Refund ({@code ..._REFUND_FAILED}) sont au moins {@code maxFailures} ET que le premier date d'avant
     * {@code failuresBefore} : les échecs doivent couvrir une durée, pas seulement trois passages rapprochés (une panne
     * Stripe d'une demi-heure ne doit pas faire abandonner). L'alerte {@code AMEND_STALE_REFUND_FAIL_} levée au premier
     * échec reste, à traiter à la main ; la PaymentIntent ne bloque plus le lot.
     */
    @Query(value = """
            SELECT DISTINCT ON (s.payload->>'piId')
                   CAST(s.entity_id AS VARCHAR), s.payload->>'bidId', s.payload->>'piId'
            FROM audit_log s
            WHERE s.entity_type = 'BID_AMENDMENT' AND s.action = 'BID_AMENDMENT_STALE_COMMISSION'
              AND s.created_at >= :since
              AND s.payload->>'piId' IS NOT NULL AND s.payload->>'piId' <> 'null'
              AND NOT EXISTS (
                  SELECT 1 FROM audit_log r
                  WHERE r.entity_type = 'BID_AMENDMENT' AND r.entity_id = s.entity_id
                    AND r.action IN ('BID_AMENDMENT_STALE_COMMISSION_REFUNDED',
                                     'BID_AMENDMENT_STALE_COMMISSION_REFUND_CONFIRMED')
                    AND r.payload->>'piId' = s.payload->>'piId')
              AND NOT EXISTS (
                  SELECT 1 FROM audit_log f
                  WHERE f.entity_type = 'BID_AMENDMENT' AND f.entity_id = s.entity_id
                    AND f.action = 'BID_AMENDMENT_STALE_COMMISSION_REFUND_FAILED'
                    AND f.payload->>'piId' = s.payload->>'piId'
                  HAVING COUNT(*) >= :maxFailures AND MIN(f.created_at) < CAST(:failuresBefore AS TIMESTAMP))
            ORDER BY s.payload->>'piId', s.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findStaleCommissionsWithoutRefund(@Param("since") LocalDateTime since,
                                                     @Param("maxFailures") int maxFailures,
                                                     @Param("failuresBefore") LocalDateTime failuresBefore,
                                                     @Param("limit") int limit);

    /**
     * Filet : modifications acquises ni versées ni remboursées, colis terminé (statut stable) depuis avant
     * {@code before} :
     * <ul>
     *   <li>carte ou mobile money ({@code onlineStatuses}) sur un colis livré ou terminé sans livraison
     *       ({@code endedStatuses}) ;</li>
     *   <li>espèces encore {@code ACCEPTED} sur un colis terminé sans livraison ({@code undeliveredStatuses}) : rien
     *       n'est à verser en espèces après une livraison, mais un colis mort ne doit plus porter de modification acquise.</li>
     * </ul>
     * Une modification dont l'alerte {@code alertPrefix + id} est déjà ouverte est écartée : au-delà d'un lot de
     * modifications bloquées, les nouvelles sont encore signalées.
     */
    @Query("""
            SELECT a.id FROM BidAmendmentEntity a, com.yadony.api.matching.BidEntity b
            WHERE b.id = a.bidId AND b.updatedAt < :before
              AND ((a.rail <> com.yadony.api.payments.supplement.BidSupplementRail.CASH
                    AND a.status IN :onlineStatuses AND b.status IN :endedStatuses)
                   OR (a.rail = com.yadony.api.payments.supplement.BidSupplementRail.CASH
                       AND a.status = com.yadony.api.payments.amendment.BidAmendmentStatus.ACCEPTED
                       AND b.status IN :undeliveredStatuses))
              AND NOT EXISTS (SELECT 1 FROM com.yadony.api.admin.AdminAlertEntity al
                              WHERE al.type = CONCAT(:alertPrefix, CAST(a.id AS String)) AND al.resolved = false)
            ORDER BY a.createdAt
            """)
    List<UUID> findIdsUnsettledAfterBidEnd(@Param("onlineStatuses") Collection<BidAmendmentStatus> onlineStatuses,
                                           @Param("endedStatuses") Collection<com.yadony.api.matching.BidStatus> endedStatuses,
                                           @Param("undeliveredStatuses") Collection<com.yadony.api.matching.BidStatus> undeliveredStatuses,
                                           @Param("alertPrefix") String alertPrefix,
                                           @Param("before") LocalDateTime before, Pageable page);
}
