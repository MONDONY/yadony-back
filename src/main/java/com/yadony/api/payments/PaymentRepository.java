package com.yadony.api.payments;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.yadony.api.matching.dto.AnnouncementRevenueRow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByBidId(UUID bidId);

    Optional<PaymentEntity> findByNegotiationThreadId(UUID negotiationThreadId);

    Optional<PaymentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    Optional<PaymentEntity> findByStripeChargeId(String chargeId);

    List<PaymentEntity> findByStatus(PaymentStatus status);

    /** Story 6.5 — Find all payments in a given status whose escrow started before the given threshold. */
    List<PaymentEntity> findByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime threshold);

    List<PaymentEntity> findAllByCreatedAtBetweenOrderByCreatedAtAsc(LocalDateTime from, LocalDateTime to);

    /** Story 9.8 — GDPR: check active escrow payments for given bid IDs. */
    boolean existsByBidIdInAndStatus(List<UUID> bidIds, PaymentStatus status);

    /**
     * Atomic status transition ESCROW → RELEASED.
     * Returns 1 if the row was updated, 0 if it was already in a non-ESCROW state.
     * Using this instead of a read-then-write prevents double-capture race conditions.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = 'RELEASED', p.escrowReleasedAt = :releasedAt WHERE p.id = :id AND p.status = 'ESCROW'")
    int markReleasedIfEscrow(@Param("id") UUID id, @Param("releasedAt") LocalDateTime releasedAt);

    /**
     * Atomic capture-once CAS guard. Returns 1 if the row was updated (first capture),
     * 0 if already captured or not in ESCROW status.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.capturedAt = :now WHERE p.id = :id AND p.capturedAt IS NULL AND p.status = com.yadony.api.payments.PaymentStatus.ESCROW")
    int markCapturedIfEscrow(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Atomic status transition ESCROW → REFUNDED.
     * Returns 1 if the row was updated, 0 if it was already in a non-ESCROW state.
     * Guards the admin manual refund against a double-refund race.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = 'REFUNDED' WHERE p.id = :id AND p.status = 'ESCROW'")
    int markRefundedIfEscrow(@Param("id") UUID id);

    /**
     * Verrou pessimiste sur le paiement d'un bid : sérialise deux initiations de deposit
     * mobile money concurrentes (deux appuis, deux appareils).
     *
     * <p>Verrou JPQL {@code PESSIMISTIC_WRITE} (Hibernate émet {@code FOR NO KEY UPDATE} sur
     * PostgreSQL) et surtout pas un {@code FOR UPDATE} natif : l'INSERT d'une opération pawaPay
     * (FK vers {@code payments.id}, transaction REQUIRES_NEW) prend un {@code KEY SHARE} sur le
     * paiement, compatible avec NO KEY UPDATE, bloqué par FOR UPDATE. Non exécutable sous H2
     * avec le dialecte PostgreSQL forcé : couvert par les tests unitaires des appelants et par
     * PostgreSQL.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentEntity p WHERE p.bidId = :bidId")
    Optional<PaymentEntity> findByBidIdForUpdate(@Param("bidId") UUID bidId);

    /**
     * Séquestre mobile money : PENDING → ESCROW, une seule fois, en mémorisant le deposit
     * pawaPay qui l'a financé. 0 = déjà en ESCROW (rejeu) ou déjà CANCELLED (deadline
     * passée pendant la saisie du PIN — l'appelant rembourse alors).
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = 'ESCROW', p.capturedAt = :now, p.pawapayDepositId = :opId "
            + "WHERE p.id = :id AND p.status = 'PENDING'")
    int markEscrowIfPending(@Param("id") UUID id, @Param("opId") UUID opId, @Param("now") Instant now);

    /** PENDING → CANCELLED (deadline mobile money dépassée, ou remboursement d'un paiement jamais encaissé). */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = 'CANCELLED' WHERE p.id = :id AND p.status = 'PENDING'")
    int markCancelledIfPending(@Param("id") UUID id);

    /**
     * Rail pawaPay (tâche 16, Ronde 1, point 1 — CRITIQUE) : pose {@code pawapay_payout_id} par
     * un UPDATE ciblé, symétrique de {@link #markEscrowIfPending} qui pose déjà
     * {@code pawapayDepositId} dans son propre bulk. À utiliser {@code TOUJOURS} à la place d'un
     * {@code payment.setPawapayPayoutId(...)} sur l'entité gérée juste après
     * {@link #markReleasedIfEscrow} : ce claim est un bulk JPQL {@code @Modifying} SANS
     * {@code clearAutomatically} — la base passe {@code RELEASED} mais l'entité chargée en amont
     * (ex. par {@code DeliveryEventListener#handleDeliveryConfirmed}) garde son ancien snapshot
     * {@code ESCROW} en mémoire. {@code PaymentEntity} n'a ni {@code @DynamicUpdate} ni
     * {@code @Version} : un setter sur cette entité la rend sale, et au flush (souvent au commit
     * de la transaction) Hibernate régénère un UPDATE de TOUTES les colonnes avec les valeurs
     * en mémoire — {@code status = 'ESCROW'} écraserait alors silencieusement le
     * {@code RELEASED} tout juste posé, chaque livraison mobile money. Voir
     * {@code PaymentRepositoryMobileMoneyTest#markReleasedIfEscrow_thenAttachPayoutId_doesNotRevertStatus}.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.pawapayPayoutId = :opId WHERE p.id = :id")
    int attachPayoutId(@Param("id") UUID id, @Param("opId") UUID opId);

    /**
     * Vrai si l'utilisateur a au moins un paiement en séquestre actif, qu'il soit
     * expéditeur ou voyageur, quel que soit le flux (bid direct ou négociation).
     *
     * <p>Deux chemins sont couverts :</p>
     * <ul>
     *   <li><b>Flux bid direct</b> — {@code p.bid_id} est renseigné ; on remonte via
     *       {@code bids} (expéditeur = {@code b.sender_id}) et via
     *       {@code announcements} (voyageur = {@code a.traveler_id}).</li>
     *   <li><b>Flux négociation / trajet dédié</b> — {@code p.bid_id} est NULL et le
     *       paiement est keyed sur {@code negotiation_thread_id}. Le voyageur est
     *       directement {@code t.traveler_id} ; l'expéditeur est
     *       {@code package_requests.sender_id} via {@code t.package_request_id}.</li>
     * </ul>
     *
     * <p><b>Requête native délibérée</b> — même motif que
     * {@link com.yadony.api.auth.UserRepository#findByIdIncludingDeleted} : les entités
     * {@code BidEntity}, {@code AnnouncementEntity}, {@code NegotiationThreadEntity} et
     * {@code PackageRequestEntity} portent {@code @Where} / {@code @SQLRestriction}
     * ({@code deleted_at IS NULL}). Hibernate injecte ces filtres dans la clause {@code ON}
     * des {@code LEFT JOIN} JPQL, rendant la jointure {@code NULL} dès que l'objet métier
     * a été soft-deleted. Conséquence : un paiement bel et bien en séquestre devenait
     * invisible, permettant l'anonymisation d'un compte dont de l'argent d'un tiers
     * était encore bloqué chez Stripe. La requête native court-circuite ces filtres ;
     * seul {@code payments.deleted_at IS NULL} est conservé (un paiement supprimé
     * n'engage plus d'argent réel).</p>
     */
    @Query(value = """
        SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
        FROM payments p
        LEFT JOIN bids b              ON p.bid_id = b.id
        LEFT JOIN announcements a     ON b.announcement_id = a.id
        LEFT JOIN negotiation_threads t  ON p.negotiation_thread_id = t.id
        LEFT JOIN package_requests pr ON t.package_request_id = pr.id
        WHERE p.deleted_at IS NULL
          AND p.status = 'ESCROW'
          AND (
                b.sender_id   = :userId
             OR a.traveler_id = :userId
             OR t.traveler_id = :userId
             OR pr.sender_id  = :userId
              )
    """, nativeQuery = true)
    boolean hasActiveEscrowForUser(@Param("userId") UUID userId);

    // Un paiement du flux négociation / trajet dédié a bidId = NULL (keyé sur le
    // thread) : LEFT JOIN + attribution via t.travelerId, sinon l'INNER JOIN sur
    // le bid jetait ces revenus (KPI « Revenus » à 0 pour les deals carte négociés).
    // Groupé par devise, jamais sommé à plat : les paiements d'un voyageur peuvent
    // mêler EUR et XOF, et « SUM(amount) » toutes devises confondues additionnait
    // des grandeurs incommensurables. UPPER : la colonne a historiquement porté
    // 'eur' minuscule (V196), normalisée en V236 — le UPPER rend la requête
    // insensible à l'ordre d'exécution des migrations et aux lignes futures.
    @Query("""
        SELECT new com.yadony.api.payments.dto.CurrencyAmountRow(
            UPPER(p.currency), SUM(p.amount - p.commissionAmount))
        FROM PaymentEntity p
        LEFT JOIN com.yadony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.yadony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.yadony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        WHERE (a.travelerId = :travelerId OR t.travelerId = :travelerId)
          AND p.status = :status
          AND p.createdAt BETWEEN :from AND :to
        GROUP BY UPPER(p.currency)
    """)
    List<com.yadony.api.payments.dto.CurrencyAmountRow> sumCapturedRevenueForTravelerByCurrency(
            @Param("travelerId") UUID travelerId,
            @Param("status") PaymentStatus status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /**
     * Revenus agrégés par annonce sur une période, alimentés par les paiements du
     * statut donné (RELEASED pour les analytics). Même clause WHERE que
     * {@link #sumCapturedRevenueForTraveler} (voyageur + statut + {@code createdAt}
     * dans la période), simplement groupée par annonce : la somme des
     * {@code gross − commission} de toutes les lignes se réconcilie donc exactement
     * avec le KPI « Revenus nets ». La commission remontée est celle réellement
     * prélevée (somme des {@code commissionAmount}, overrides figés inclus).
     */
    // Annonce effective = celle du bid (deals directs) ou, pour un paiement de
    // thread (bidId NULL), l'annonce du voyageur liée au thread — COALESCE des
    // deux, sinon les revenus négociés disparaissaient de la ventilation par annonce.
    @Query("""
        SELECT new com.yadony.api.matching.dto.AnnouncementRevenueRow(
            ann.id, ann.departureCity, ann.arrivalCity, ann.departureDate, UPPER(ann.currency),
            COUNT(p), COALESCE(SUM(p.amount), 0), COALESCE(SUM(p.commissionAmount), 0))
        FROM PaymentEntity p
        LEFT JOIN com.yadony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.yadony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.yadony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        LEFT JOIN com.yadony.api.matching.AnnouncementEntity ta ON t.travelerAnnouncementId = ta.id
        JOIN com.yadony.api.matching.AnnouncementEntity ann ON ann.id = COALESCE(a.id, ta.id)
        WHERE ann.travelerId = :travelerId
          AND p.status = :status
          AND p.createdAt BETWEEN :from AND :to
        GROUP BY ann.id, ann.departureCity, ann.arrivalCity, ann.departureDate, ann.currency
        ORDER BY ann.departureDate DESC
    """)
    List<AnnouncementRevenueRow> findReleasedRevenueByAnnouncement(
            @Param("travelerId") UUID travelerId,
            @Param("status") PaymentStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** Total tous temps, groupé par devise — voir {@link #sumCapturedRevenueForTravelerByCurrency}. */
    @Query("""
        SELECT new com.yadony.api.payments.dto.CurrencyAmountRow(
            UPPER(p.currency), SUM(p.amount - p.commissionAmount))
        FROM PaymentEntity p
        LEFT JOIN com.yadony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.yadony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.yadony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        WHERE (a.travelerId = :travelerId OR t.travelerId = :travelerId)
          AND p.status = :status
        GROUP BY UPPER(p.currency)
    """)
    List<com.yadony.api.payments.dto.CurrencyAmountRow> sumTotalCapturedRevenueForTravelerByCurrency(
            @Param("travelerId") UUID travelerId,
            @Param("status") PaymentStatus status);

    @Query("""
        SELECT p FROM PaymentEntity p
        LEFT JOIN com.yadony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.yadony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.yadony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        WHERE (a.travelerId = :travelerId OR t.travelerId = :travelerId)
          AND p.status = 'RELEASED'
          AND p.createdAt BETWEEN :from AND :to
        ORDER BY p.createdAt ASC
    """)
    List<PaymentEntity> findReleasedByTravelerAndYear(
            @Param("travelerId") UUID travelerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query(value = """
            SELECT p.* FROM payments p
            WHERE p.deleted_at IS NULL
              AND (CAST(:status AS VARCHAR) IS NULL OR p.status = :status)
              AND (CAST(:from AS TIMESTAMP) IS NULL OR p.created_at >= CAST(:from AS TIMESTAMP))
              AND (CAST(:to AS TIMESTAMP) IS NULL OR p.created_at <= CAST(:to AS TIMESTAMP))
            ORDER BY p.created_at DESC
            """,
           countQuery = """
            SELECT COUNT(*) FROM payments p
            WHERE p.deleted_at IS NULL
              AND (CAST(:status AS VARCHAR) IS NULL OR p.status = :status)
              AND (CAST(:from AS TIMESTAMP) IS NULL OR p.created_at >= CAST(:from AS TIMESTAMP))
              AND (CAST(:to AS TIMESTAMP) IS NULL OR p.created_at <= CAST(:to AS TIMESTAMP))
            """,
           nativeQuery = true)
    Page<PaymentEntity> findAdminFiltered(
            @Param("status") String status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            Pageable pageable);
}
