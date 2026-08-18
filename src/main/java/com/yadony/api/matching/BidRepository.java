package com.yadony.api.matching;

import com.yadony.api.matching.dto.AnnouncementRevenueRow;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
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

public interface BidRepository extends JpaRepository<BidEntity, UUID> {

    long countByAnnouncementId(UUID announcementId);

    /** Bids dont le délai de retour (J+3) est dépassé sans retour confirmé (tranche D). */
    List<BidEntity> findByReturnDeadlineBeforeAndReturnedAtIsNullAndReturnExpiredNotifiedAtIsNull(
            LocalDateTime now);

    /** Retours arrivant à échéance sous 24 h et n'ayant pas encore été rappelés. */
    List<BidEntity> findByReturnDeadlineBetweenAndReturnWarningSentAtIsNullAndReturnedAtIsNull(
            LocalDateTime from, LocalDateTime to);

    long countByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    @Query("""
        SELECT COUNT(b) FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
    """)
    long countByAnnouncementTravelerIdAndStatus(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status);

    @Query("""
        SELECT COUNT(b) FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status IN :statuses
    """)
    long countByAnnouncementTravelerIdAndStatusIn(
            @Param("travelerId") UUID travelerId,
            @Param("statuses") java.util.Collection<BidStatus> statuses);

    /**
     * Refus EXPLICITES du voyageur : bids REJECTED, en excluant ceux passés en
     * REJECTED parce que l'annonce a été supprimée (marqueur
     * {@code BidEntity.REJECTION_ANNOUNCEMENT_DELETED}). Sert au dénominateur du
     * taux d'acceptation, pour ne pas pénaliser une suppression de trajet.
     */
    @Query("""
        SELECT COUNT(b) FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = com.yadony.api.matching.BidStatus.REJECTED
          AND (b.rejectionReason IS NULL OR b.rejectionReason <> 'ANNOUNCEMENT_DELETED')
    """)
    long countExplicitRejectionsForTraveler(@Param("travelerId") UUID travelerId);

    @Query("""
        SELECT COUNT(b) FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.createdAt BETWEEN :from AND :to
    """)
    long countDeliveredBidsForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    @Query("""
        SELECT COALESCE(SUM(b.weightKg), 0)
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.createdAt BETWEEN :from AND :to AND b.deletedAt IS NULL
    """)
    java.math.BigDecimal sumDeliveredKgForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /**
     * Revenu net des deals réglés en ESPÈCES sur la période. Le cash ne crée
     * aucun PaymentEntity (argent de la main à la main, Yadony ne prélève que sa
     * commission à part) : le revenu carte, payment-based, l'ignore donc. On le
     * reconstitue depuis le bid livré = {@code negotiatedNetEur} (net voyageur
     * figé au trip-linking, Modèle B : l'expéditeur paie gross = net×(1+taux)).
     * Filtré {@code paymentMethod = CASH} pour ne pas doubler les deals carte
     * déjà comptés par {@code PaymentRepository.sumCapturedRevenueForTraveler}.
     * Même fenêtre que {@link #sumDeliveredKgForTraveler} ({@code b.createdAt}).
     */
    @Query("""
        SELECT COALESCE(SUM(b.negotiatedNetEur), 0)
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.paymentMethod = :method
          AND b.createdAt BETWEEN :from AND :to AND b.deletedAt IS NULL
    """)
    java.math.BigDecimal sumCashNetRevenueForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("method") PaymentMethod method,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /** Total tous temps du revenu net cash — voir {@link #sumCashNetRevenueForTraveler}. */
    @Query("""
        SELECT COALESCE(SUM(b.negotiatedNetEur), 0)
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.paymentMethod = :method AND b.deletedAt IS NULL
    """)
    java.math.BigDecimal sumTotalCashNetRevenueForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("method") PaymentMethod method);

    /**
     * Revenu cash agrégé par annonce, pour la ventilation « transactions » du
     * cockpit pro. Miroir de {@code PaymentRepository.findReleasedRevenueByAnnouncement}
     * côté espèces : gross = net = {@code negotiatedNetEur} (le voyageur encaisse
     * le net en cash), commission = 0 (la commission Yadony du cash est prélevée à
     * part et son montant n'est pas figé sur le bid). Ainsi la somme des colonnes
     * Net (carte + cash) se réconcilie exactement avec le KPI « Revenus ».
     */
    @Query("""
        SELECT new com.yadony.api.matching.dto.AnnouncementRevenueRow(
            a.id, a.departureCity, a.arrivalCity, a.departureDate,
            COUNT(b), COALESCE(SUM(b.negotiatedNetEur), 0), COALESCE(SUM(b.negotiatedNetEur * 0), 0))
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.paymentMethod = :method
          AND b.createdAt BETWEEN :from AND :to AND b.deletedAt IS NULL
        GROUP BY a.id, a.departureCity, a.arrivalCity, a.departureDate
        ORDER BY a.departureDate DESC
    """)
    List<AnnouncementRevenueRow> findCashRevenueByAnnouncement(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("method") PaymentMethod method,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /**
     * Counts only bids that are currently visible to the traveler on their announcement
     * (PENDING demands awaiting traveler action). Excludes AWAITING_PAYMENT (sender hasn't paid),
     * CANCELLED, REJECTED, COMPLETED — none of which appear in the traveler's pending list.
     */
    @Query("SELECT COUNT(b) FROM BidEntity b WHERE b.announcementId = :announcementId " +
           "AND b.status IN (com.yadony.api.matching.BidStatus.PENDING, " +
           "                 com.yadony.api.matching.BidStatus.PAYMENT_ESCROWED) " +
           "AND b.deletedByTraveler = false")
    long countVisibleByAnnouncementId(@Param("announcementId") UUID announcementId);

    /**
     * Batch variant of {@link #countVisibleByAnnouncementId}: returns one row per
     * announcement ID in {@code ids} with its visible-bid count.
     * Uses the same visibility filter (PENDING | PAYMENT_ESCROWED, not deleted by traveler).
     * Announcement IDs with zero visible bids are NOT returned (GROUP BY omits them);
     * callers must default absent keys to 0.
     *
     * @return list of Object[] where [0]=announcementId (UUID), [1]=count (Long)
     */
    @Query("SELECT b.announcementId, COUNT(b) FROM BidEntity b " +
           "WHERE b.announcementId IN :ids " +
           "AND b.status IN (com.yadony.api.matching.BidStatus.PENDING, " +
           "                 com.yadony.api.matching.BidStatus.PAYMENT_ESCROWED) " +
           "AND b.deletedByTraveler = false " +
           "GROUP BY b.announcementId")
    List<Object[]> countVisibleByAnnouncementIds(@Param("ids") java.util.Collection<UUID> ids);

    List<BidEntity> findByAnnouncementIdAndStatusIn(UUID announcementId, List<BidStatus> statuses);

    long countByAnnouncementIdAndStatusIn(UUID announcementId, List<BidStatus> statuses);

    boolean existsByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    boolean existsByAnnouncementIdAndStatusIn(UUID announcementId, List<BidStatus> statuses);

    boolean existsBySenderIdAndAnnouncementIdAndStatusIn(UUID senderId, UUID announcementId, List<BidStatus> statuses);

    Optional<BidEntity> findBySenderIdAndAnnouncementIdAndStatus(UUID senderId, UUID announcementId, BidStatus status);

    List<BidEntity> findByAnnouncementId(UUID announcementId);

    List<BidEntity> findByAnnouncementIdAndStatusNotIn(UUID announcementId, Collection<BidStatus> statuses);

    boolean existsByAnnouncementIdAndSenderIdAndStatusNotIn(UUID announcementId, UUID senderId,
                                                            Collection<BidStatus> statuses);

    List<BidEntity> findByAnnouncementIdAndStatus(UUID announcementId, BidStatus status);

    List<BidEntity> findBySenderId(UUID senderId);

    List<BidEntity> findBySenderIdAndStatusIn(UUID senderId, List<BidStatus> statuses);

    // For H-2 alert scheduler: ACCEPTED bids with handover starting in ≤ 2h, not yet alerted, not confirmed
    Optional<BidEntity> findByTrackingNumber(String trackingNumber);

    Optional<BidEntity> findByTrackingToken(String trackingToken);

    Optional<BidEntity> findByPaymentIntentId(String paymentIntentId);

    Optional<BidEntity> findByLinkedNegotiationThreadId(UUID linkedNegotiationThreadId);

    List<BidEntity> findByStatusAndAwaitingPaymentExpiresAtBefore(
            BidStatus status, LocalDateTime threshold);

    // Rappel H-2 : l'alerte se cale désormais sur la date limite de dépôt (il
    // n'y a plus de début de fenêtre). On prévient donc l'expéditeur quand il
    // ne lui reste que 2 h pour remettre son colis, ce qui est le moment utile.
    @Query("SELECT b FROM BidEntity b WHERE b.status = 'ACCEPTED' " +
           "AND b.handoverDeadline IS NOT NULL " +
           "AND b.handoverDeadline <= :threshold " +
           "AND b.handoverDeadline > :now " +
           "AND b.voyageurConfirmed = false " +
           "AND b.h2AlertSentAt IS NULL")
    List<BidEntity> findBidsNeedingH2Alert(@Param("now") LocalDateTime now,
                                            @Param("threshold") LocalDateTime threshold);

    // No-show detection: ACCEPTED bids with handoverDeadline > 1h ago, no DEPART scan, not yet marked NO_SHOW
    @Query("SELECT b FROM BidEntity b WHERE b.status = 'ACCEPTED' " +
           "AND b.handoverDeadline IS NOT NULL " +
           "AND b.handoverDeadline < :cutoff " +
           "AND b.noShowAt IS NULL " +
           "AND b.deletedAt IS NULL " +
           "AND NOT EXISTS (SELECT t FROM TrackingEventEntity t WHERE t.bidId = b.id AND t.eventType = 'DEPART')")
    List<BidEntity> findNoShowBids(@Param("cutoff") LocalDateTime cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BidEntity b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<BidEntity> findByIdForUpdate(@Param("id") UUID id);

    // Completed deliveries for a given traveler (via announcement ownership)
    @Query("SELECT b FROM BidEntity b JOIN AnnouncementEntity a ON b.announcementId = a.id " +
           "WHERE a.travelerId = :travelerId AND b.status = 'COMPLETED'")
    List<BidEntity> findCompletedBidsByTravelerId(@Param("travelerId") UUID travelerId);

    /**
     * Colis d'un voyageur, paginés et filtrés côté SQL.
     *
     * <p>{@code hiddenStatuses} n'est pas une commodité d'appelant : cette surface
     * filtre en JPQL, là où ses deux sœurs ({@code getMyBids}, liste par trajet)
     * filtrent en flux Java après lecture. Elle n'avait donc jamais reçu leur
     * exclusion des discussions de prix, et laissait remonter les fils dans
     * {@code GET /bids/traveler/me}. Le paramètre porte l'ensemble nommé plutôt
     * qu'un littéral pour que la requête suive {@link BidStatus#NEGOTIATION_STATUSES}
     * si celui-ci s'étend.
     */
    @Query("""
        SELECT b FROM BidEntity b JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId
          AND b.status NOT IN :hiddenStatuses
          AND (:status IS NULL OR b.status = :status)
          AND (:announcementId IS NULL OR b.announcementId = :announcementId)
          AND (:q IS NULL OR UPPER(b.trackingNumber) LIKE UPPER(CONCAT('%', CAST(:q AS string), '%')))
        ORDER BY b.createdAt DESC
        """)
    Page<BidEntity> findByTravelerIdFiltered(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("announcementId") UUID announcementId,
            @Param("q") String q,
            @Param("hiddenStatuses") Collection<BidStatus> hiddenStatuses,
            Pageable pageable);

    /**
     * Demandes PENDING que le voyageur a laissées sans réponse au-delà du délai.
     *
     * <p>L'horloge est {@code COALESCE(pendingSince, createdAt)}, pas {@code createdAt} :
     * un accord de négociation entre dans la file du voyageur à l'acceptation, très
     * longtemps après la création du fil (jusqu'à 72 h d'échanges). Sur {@code createdAt}
     * seul, le cas NOMINAL — un fil de plus de 24 h — voyait son accord détruit au tick
     * suivant, avant même que le voyageur ait pu régler la commission.
     * Cf. {@code BidEntity.pendingSince}.
     */
    @Query("""
        SELECT b FROM BidEntity b, AnnouncementEntity a
        WHERE b.announcementId = a.id
          AND b.status = com.yadony.api.matching.BidStatus.PENDING
          AND COALESCE(b.pendingSince, b.createdAt) < :minGraceThreshold
          AND (
                COALESCE(b.pendingSince, b.createdAt) < :twentyFourHoursAgo
             OR a.departureDate <= :halfDayThresholdDate
          )
        """)
    List<BidEntity> findPendingTimedOut(
            @Param("twentyFourHoursAgo") LocalDateTime twentyFourHoursAgo,
            @Param("halfDayThresholdDate") java.time.LocalDate halfDayThresholdDate,
            @Param("minGraceThreshold") LocalDateTime minGraceThreshold
    );

    // Retourne le bid COMPLETED le plus récent pour lequel userId n'a pas encore noté
    // (en tant qu'expéditeur OU voyageur via la jointure avec announcements)
    @Query(nativeQuery = true, value = """
        SELECT b.* FROM bids b
        JOIN announcements a ON b.announcement_id = a.id
        WHERE b.status = 'COMPLETED'
          AND b.deleted_at IS NULL
          AND (b.sender_id = :userId OR a.traveler_id = :userId)
          AND NOT EXISTS (
              SELECT 1 FROM ratings r
              WHERE r.bid_id = b.id
                AND r.rater_id = :userId
                AND r.deleted_at IS NULL
          )
        ORDER BY b.updated_at DESC
        LIMIT 1
        """)
    Optional<BidEntity> findPendingRatingForUser(@Param("userId") UUID userId);

    /**
     * Counts completed deliveries for a given sender.
     * Used by {@code DeliveryConfirmedReferralListener} to detect the first delivery.
     * Explicit deleted_at filter because custom @Query bypasses @Where in some Hibernate 6 versions.
     */
    @Query("SELECT COUNT(b) FROM BidEntity b WHERE b.senderId = :senderId AND b.status = :status AND b.deletedAt IS NULL")
    long countByStatusAndSenderId(@Param("status") BidStatus status, @Param("senderId") UUID senderId);

    /**
     * Counts parcels the user sent over a period, for the "Envois" activity statistic.
     * Excludes bids that never became a real shipment (never paid, refused, cancelled).
     */
    @Query("""
        SELECT COUNT(b)
        FROM BidEntity b
        WHERE b.senderId = :senderId
          AND b.createdAt BETWEEN :from AND :to
          AND b.deletedAt IS NULL
          AND b.status NOT IN :excludedStatuses
    """)
    long countParcelsSentBySender(
            @Param("senderId") UUID senderId,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("excludedStatuses") java.util.Collection<BidStatus> excludedStatuses);

    /**
     * Returns past completed bookings for a sender, with traveler info and how many trips
     * that sender has done with each traveler. Used by the rebooking feature.
     */
    @Query(value = """
        SELECT
            b.id                                               AS bid_id,
            a.traveler_id                                      AS traveler_id,
            u.first_name || ' ' || u.last_name                AS traveler_name,
            CASE WHEN u.is_pro_account THEN 'PRO' ELSE NULL END AS traveler_badge,
            a.departure_city                                   AS departure_city,
            a.arrival_city                                     AS arrival_city,
            a.departure_date                                   AS last_trip_date,
            COUNT(b2.id) OVER (PARTITION BY a2.traveler_id)    AS completed_trips
        FROM bids b
        JOIN announcements a  ON b.announcement_id = a.id AND a.deleted_at IS NULL
        JOIN users u          ON a.traveler_id = u.id
        JOIN bids b2          ON b2.sender_id = b.sender_id
                             AND b2.status = 'COMPLETED'
                             AND b2.deleted_at IS NULL
        JOIN announcements a2 ON b2.announcement_id = a2.id
                             AND a2.traveler_id = a.traveler_id
                             AND a2.deleted_at IS NULL
        WHERE b.sender_id = :senderId
          AND b.status = 'COMPLETED'
          AND b.deleted_at IS NULL
        ORDER BY a.departure_date DESC
        """, nativeQuery = true)
    List<Object[]> findPastBookingsBySender(@Param("senderId") UUID senderId);

    List<BidEntity> findByCommissionStatusAndUpdatedAtBefore(
            CommissionStatus commissionStatus, LocalDateTime before);

    /**
     * True if there is at least one active (in-flight) transaction between two users,
     * in either direction (sender↔traveler). Used to prevent blocking a user mid-deal.
     */
    @Query("""
        SELECT COUNT(b) > 0 FROM BidEntity b
        JOIN AnnouncementEntity a ON a.id = b.announcementId
        WHERE b.status IN :activeStatuses AND (
              (b.senderId = :userA AND a.travelerId = :userB)
           OR (b.senderId = :userB AND a.travelerId = :userA))
        """)
    boolean hasActiveTransactionBetween(
            @Param("userA") UUID userA,
            @Param("userB") UUID userB,
            @Param("activeStatuses") List<BidStatus> activeStatuses);

    @Query(value = """
        SELECT b.* FROM bids b
        WHERE b.deleted_at IS NULL
          AND (CAST(:status AS VARCHAR) IS NULL OR b.status = :status)
          AND (CAST(:announcementId AS VARCHAR) IS NULL OR b.announcement_id = CAST(:announcementId AS UUID))
          AND (CAST(:q AS VARCHAR) IS NULL OR b.tracking_number ILIKE '%' || :q || '%')
          AND (CAST(:from AS TIMESTAMP) IS NULL OR b.created_at >= CAST(:from AS TIMESTAMP))
          AND (CAST(:to AS TIMESTAMP) IS NULL OR b.created_at <= CAST(:to AS TIMESTAMP))
        ORDER BY b.created_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM bids b
        WHERE b.deleted_at IS NULL
          AND (CAST(:status AS VARCHAR) IS NULL OR b.status = :status)
          AND (CAST(:announcementId AS VARCHAR) IS NULL OR b.announcement_id = CAST(:announcementId AS UUID))
          AND (CAST(:q AS VARCHAR) IS NULL OR b.tracking_number ILIKE '%' || :q || '%')
          AND (CAST(:from AS TIMESTAMP) IS NULL OR b.created_at >= CAST(:from AS TIMESTAMP))
          AND (CAST(:to AS TIMESTAMP) IS NULL OR b.created_at <= CAST(:to AS TIMESTAMP))
        """,
        nativeQuery = true)
    Page<BidEntity> findAdminFiltered(
            @Param("status") String status,
            @Param("announcementId") String announcementId,
            @Param("q") String q,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            Pageable pageable);

    /**
     * Expéditeurs "fidèles" : ceux ayant déjà eu un bid ACCEPTED avec ce voyageur
     * sur exactement ce corridor (ville de départ + ville d'arrivée). Utilisé par
     * la règle d'automatisation "notify_loyal_senders" (Task 5) à la publication
     * d'une nouvelle annonce.
     */
    @Query("SELECT DISTINCT b.senderId FROM BidEntity b " +
           "JOIN AnnouncementEntity a ON a.id = b.announcementId " +
           "WHERE a.travelerId = :travelerId AND a.departureCity = :departureCity " +
           "AND a.arrivalCity = :arrivalCity AND b.status = com.yadony.api.matching.BidStatus.ACCEPTED " +
           "AND b.deletedAt IS NULL")
    List<UUID> findLoyalSenderIds(@Param("travelerId") UUID travelerId,
                                  @Param("departureCity") String departureCity,
                                  @Param("arrivalCity") String arrivalCity);

    /** Fils de négociation où l'utilisateur est expéditeur ou voyageur du trajet. */
    @Query("""
        SELECT b FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE b.status = com.yadony.api.matching.BidStatus.NEGOTIATING
          AND (b.senderId = :userId OR a.travelerId = :userId)
          AND b.deletedAt IS NULL
        ORDER BY b.updatedAt DESC
    """)
    List<BidEntity> findNegotiationsForUser(@Param("userId") UUID userId);

    /**
     * Fils inactifs depuis le seuil : plus aucun message échangé.
     *
     * <p>Le critère est la date du dernier message, PAS {@code updatedAt} du bid :
     * marquer un fil comme lu écrit sur le bid, et l'horloge d'inactivité repartirait
     * alors de zéro à chaque ouverture de l'écran — un fil abandonné mais consulté
     * n'expirerait jamais.
     */
    @Query("""
        SELECT b FROM BidEntity b
        WHERE b.status = com.yadony.api.matching.BidStatus.NEGOTIATING
          AND b.deletedAt IS NULL
          AND (SELECT MAX(m.createdAt) FROM BidNegotiationMessageEntity m
               WHERE m.bidId = b.id) < :threshold
    """)
    List<BidEntity> findStaleNegotiations(@Param("threshold") LocalDateTime threshold);

    /**
     * Fils encore ouverts sur un trajet déjà parti. Complète
     * {@link #findStaleNegotiations} : un fil actif jusqu'à la veille du départ
     * n'est jamais « inactif », mais il n'a plus d'objet une fois l'avion parti.
     */
    @Query("""
        SELECT b FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE b.status = com.yadony.api.matching.BidStatus.NEGOTIATING
          AND a.departureDate < :today
          AND b.deletedAt IS NULL
    """)
    List<BidEntity> findNegotiationsOnDepartedTrips(@Param("today") java.time.LocalDate today);
}
