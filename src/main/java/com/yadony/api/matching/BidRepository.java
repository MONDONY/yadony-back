package com.yadony.api.matching;

import com.yadony.api.admin.AdminBidQuery;
import com.yadony.api.admin.AdminBidSql;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidRepository extends JpaRepository<BidEntity, UUID> {

    /**
     * Fiche admin d'un utilisateur : colis où il est expéditeur OU voyageur (voyageur = celui de
     * l'annonce). Aucun filtre {@code deletedBy*} : ces drapeaux ne concernent que l'utilisateur.
     */
    @Query(value = """
        SELECT b FROM BidEntity b LEFT JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE (b.senderId = :userId OR a.travelerId = :userId) AND b.status NOT IN :hidden
        ORDER BY b.createdAt DESC, b.id DESC
        """,
        countQuery = """
        SELECT COUNT(b) FROM BidEntity b LEFT JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE (b.senderId = :userId OR a.travelerId = :userId) AND b.status NOT IN :hidden
        """)
    Page<BidEntity> findAdminByParty(@Param("userId") UUID userId,
                                     @Param("hidden") Collection<BidStatus> hidden, Pageable pageable);

    /**
     * Frise d'activité : les colis de l'utilisateur dont l'activité est la plus récente (livraison, sinon
     * dernière mise à jour). Appeler avec un {@code Pageable} non trié et borné.
     */
    @Query("""
        SELECT b FROM BidEntity b LEFT JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE (b.senderId = :userId OR a.travelerId = :userId) AND b.status NOT IN :hidden
        ORDER BY COALESCE(b.deliveredAt, b.updatedAt) DESC, b.id DESC
        """)
    List<BidEntity> findAdminRecentActivityByParty(@Param("userId") UUID userId,
                                                   @Param("hidden") Collection<BidStatus> hidden, Pageable pageable);

    /**
     * Frise d'activité : colis de l'utilisateur créés, livrés, mis à jour ou pris en charge (scan DEPART) depuis
     * {@code since}, du plus récent au plus ancien. Appeler avec un {@code Pageable} non trié et borné.
     */
    @Query("""
        SELECT b FROM BidEntity b LEFT JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE (b.senderId = :userId OR a.travelerId = :userId) AND b.status NOT IN :hidden
          AND (b.createdAt >= :since OR b.deliveredAt >= :since OR b.updatedAt >= :since
               OR EXISTS (SELECT 1 FROM TrackingEventEntity t WHERE t.bidId = b.id
                          AND t.eventType = com.yadony.api.tracking.TrackingEventType.DEPART
                          AND t.scannedAt >= :since))
        ORDER BY COALESCE(b.deliveredAt, b.updatedAt) DESC, b.id DESC
        """)
    List<BidEntity> findAdminActivitySinceByParty(@Param("userId") UUID userId,
                                                  @Param("since") LocalDateTime since,
                                                  @Param("hidden") Collection<BidStatus> hidden, Pageable pageable);

    /** Colis « en cours » (statuts donnés) ou livrés (COMPLETED, deliveredAt non nul) depuis {@code deliveredSince}. */
    @Query("""
        SELECT b FROM BidEntity b LEFT JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE (b.senderId = :userId OR a.travelerId = :userId)
          AND (b.status IN :statuses
               OR (b.status = com.yadony.api.matching.BidStatus.COMPLETED
                   AND b.deliveredAt IS NOT NULL AND b.deliveredAt >= :deliveredSince))
        ORDER BY b.updatedAt DESC, b.id DESC
        """)
    List<BidEntity> findAdminInProgressByParty(@Param("userId") UUID userId,
                                               @Param("statuses") Collection<BidStatus> statuses,
                                               @Param("deliveredSince") LocalDateTime deliveredSince,
                                               Pageable pageable);

    @Query("""
        SELECT COUNT(b) FROM BidEntity b LEFT JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE (b.senderId = :userId OR a.travelerId = :userId) AND b.status IN :statuses
        """)
    long countAdminByPartyAndStatusIn(@Param("userId") UUID userId,
                                      @Param("statuses") Collection<BidStatus> statuses);

    long countByAnnouncementId(UUID announcementId);

    /**
     * Lot D — les demandes reglees en especes portant une commission suivie, pour la vue
     * financiere du back-office. Le filtre sur {@code commissionStatus} n'est pas redondant :
     * une demande CASH dont la commission n'a jamais ete initiee n'a rien a montrer dans un
     * ecran de suivi des commissions.
     */
    @Query("""
        SELECT b FROM BidEntity b
        WHERE b.paymentMethod = com.yadony.api.payments.cash.PaymentMethod.CASH
          AND b.commissionStatus IS NOT NULL
        ORDER BY b.createdAt DESC
        """)
    Page<BidEntity> findCashCommissions(Pageable pageable);

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
     * reconstitue depuis le bid livré : {@code negotiatedNetEur} (net voyageur
     * figé au trip-linking, Modèle B : l'expéditeur paie gross = net×(1+taux))
     * pour un deal négocié, sinon poids × prix/kg de l'annonce + articles de
     * grille snapshotés, comme {@code BidService} pour le total affiché. Une
     * offre directe n'a pas de {@code negotiatedNetEur} : la lire seule comptait
     * chaque livraison en espèces à 0 (Sentry FLUTTER-BS).
     * Filtré {@code paymentMethod = CASH} pour ne pas doubler les deals carte
     * déjà comptés par {@code PaymentRepository.sumCapturedRevenueForTraveler}.
     * Même fenêtre que {@link #sumDeliveredKgForTraveler} ({@code b.createdAt}).
     */
    // Groupé par la devise du BID (celle de l'annonce, figée à la création) : le nom
    // hérité negotiatedNetEur ment depuis le multidevise, le montant est dans
    // b.currency. Sommer à plat mélangeait EUR et XOF.
    @Query("""
        SELECT new com.yadony.api.payments.dto.CurrencyAmountRow(
            UPPER(b.currency), SUM(COALESCE(b.negotiatedNetEur,
                COALESCE(b.weightKg * a.pricePerKg, 0)
                + (SELECT COALESCE(SUM(g.unitPriceNetSnapshot * g.quantity), 0)
                   FROM BidGridItemEntity g WHERE g.bidId = b.id))))
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.paymentMethod = :method
          AND b.createdAt BETWEEN :from AND :to AND b.deletedAt IS NULL
        GROUP BY UPPER(b.currency)
    """)
    List<com.yadony.api.payments.dto.CurrencyAmountRow> sumCashNetRevenueForTravelerByCurrency(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("method") PaymentMethod method,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /**
     * Une ligne par deal réglé en ESPÈCES et livré, dans la devise du bid. Même
     * filtre que {@link #sumCashNetRevenueForTravelerByCurrency} : la somme des
     * lignes par devise se réconcilie exactement avec ce total.
     */
    @Query("""
        SELECT new com.yadony.api.matching.dto.CashLineRow(
            a.id, a.departureCity, a.arrivalCity, a.departureDate,
            b.weightKg, UPPER(b.currency),
            COALESCE(b.negotiatedNetEur,
                COALESCE(b.weightKg * a.pricePerKg, 0)
                + (SELECT COALESCE(SUM(g.unitPriceNetSnapshot * g.quantity), 0)
                   FROM BidGridItemEntity g WHERE g.bidId = b.id)))
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.paymentMethod = :method
          AND b.createdAt BETWEEN :from AND :to AND b.deletedAt IS NULL
        ORDER BY a.departureDate DESC
    """)
    List<com.yadony.api.matching.dto.CashLineRow> findCashLinesForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("method") PaymentMethod method,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /**
     * Kg livrés groupés par trajet, du plus récent au plus ancien. Même filtre
     * que {@link #sumDeliveredKgForTraveler} : la somme des lignes égale ce total.
     */
    @Query("""
        SELECT new com.yadony.api.matching.dto.KgSoldTripRow(
            a.id, a.departureCity, a.arrivalCity, a.departureDate,
            COUNT(b), COALESCE(SUM(b.weightKg), 0))
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.createdAt BETWEEN :from AND :to AND b.deletedAt IS NULL
        GROUP BY a.id, a.departureCity, a.arrivalCity, a.departureDate
        ORDER BY a.departureDate DESC
    """)
    List<com.yadony.api.matching.dto.KgSoldTripRow> findDeliveredKgByTrip(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /** Total tous temps du revenu net cash, par devise — voir {@link #sumCashNetRevenueForTravelerByCurrency}. */
    @Query("""
        SELECT new com.yadony.api.payments.dto.CurrencyAmountRow(
            UPPER(b.currency), SUM(COALESCE(b.negotiatedNetEur,
                COALESCE(b.weightKg * a.pricePerKg, 0)
                + (SELECT COALESCE(SUM(g.unitPriceNetSnapshot * g.quantity), 0)
                   FROM BidGridItemEntity g WHERE g.bidId = b.id))))
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.paymentMethod = :method AND b.deletedAt IS NULL
        GROUP BY UPPER(b.currency)
    """)
    List<com.yadony.api.payments.dto.CurrencyAmountRow> sumTotalCashNetRevenueForTravelerByCurrency(
            @Param("travelerId") UUID travelerId,
            @Param("status") BidStatus status,
            @Param("method") PaymentMethod method);

    /**
     * Revenu cash agrégé par annonce, pour la ventilation « transactions » du
     * cockpit pro. Miroir de {@code PaymentRepository.findReleasedRevenueByAnnouncement}
     * côté espèces : gross = net, même calcul que
     * {@link #sumCashNetRevenueForTravelerByCurrency} (le voyageur encaisse le net
     * en cash), commission = 0 (la commission Yadony du cash est prélevée à
     * part et son montant n'est pas figé sur le bid). Ainsi la somme des colonnes
     * Net (carte + cash) se réconcilie exactement avec le KPI « Revenus ».
     */
    @Query("""
        SELECT new com.yadony.api.matching.dto.AnnouncementRevenueRow(
            a.id, a.departureCity, a.arrivalCity, a.departureDate, UPPER(a.currency),
            COUNT(b), COALESCE(SUM(COALESCE(b.negotiatedNetEur,
                COALESCE(b.weightKg * a.pricePerKg, 0)
                + (SELECT COALESCE(SUM(g.unitPriceNetSnapshot * g.quantity), 0)
                   FROM BidGridItemEntity g WHERE g.bidId = b.id))), 0),
            CAST(0 AS BigDecimal))
        FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status = :status
          AND b.paymentMethod = :method
          AND b.createdAt BETWEEN :from AND :to AND b.deletedAt IS NULL
        GROUP BY a.id, a.departureCity, a.arrivalCity, a.departureDate, a.currency
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

    /** Variante par lot de {@link #countByAnnouncementIdAndStatusIn} : une ligne {@code [announcementId, count]}. */
    @Query("SELECT b.announcementId, COUNT(b) FROM BidEntity b "
            + "WHERE b.announcementId IN :ids AND b.status IN :statuses GROUP BY b.announcementId")
    List<Object[]> countByAnnouncementIdsAndStatusIn(@Param("ids") java.util.Collection<UUID> ids,
                                                     @Param("statuses") List<BidStatus> statuses);

    /**
     * Net voyageur engagé sur une annonce : somme, pour les bids dont le statut est dans
     * {@code statuses}, de l'accord négocié quand il existe, sinon du barème (poids × prix
     * au kilo de l'annonce) plus les articles de grille du bid. Exprimé dans la devise de
     * l'annonce, zéro sans bid. Sert au KPI « revenus réservés » des cartes trajet.
     */
    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(
            COALESCE(b.negotiated_net_eur,
                     COALESCE(b.weight_kg, 0) * COALESCE(a.price_per_kg, 0) + COALESCE(g.grid_net, 0))
        ), 0)
        FROM bids b
        JOIN announcements a ON a.id = b.announcement_id
        LEFT JOIN (
            SELECT bid_id, SUM(unit_price_net_snapshot * quantity) AS grid_net
            FROM bid_grid_items
            GROUP BY bid_id
        ) g ON g.bid_id = b.id
        WHERE b.announcement_id = :announcementId
          AND b.status IN (:statuses)
          AND b.deleted_at IS NULL
        """)
    java.math.BigDecimal sumReservedNetByAnnouncementId(@Param("announcementId") UUID announcementId,
                                             @Param("statuses") List<String> statuses);

    /**
     * Variante par lot de {@link #sumReservedNetByAnnouncementId} (même calcul) : une ligne
     * {@code [announcement_id, net]} par annonce ayant au moins un bid dans {@code statuses}.
     */
    @Query(nativeQuery = true, value = """
        SELECT b.announcement_id, SUM(
            COALESCE(b.negotiated_net_eur,
                     COALESCE(b.weight_kg, 0) * COALESCE(a.price_per_kg, 0) + COALESCE(g.grid_net, 0))
        )
        FROM bids b
        JOIN announcements a ON a.id = b.announcement_id
        LEFT JOIN (
            SELECT bid_id, SUM(unit_price_net_snapshot * quantity) AS grid_net
            FROM bid_grid_items
            GROUP BY bid_id
        ) g ON g.bid_id = b.id
        WHERE b.announcement_id IN (:ids)
          AND b.status IN (:statuses)
          AND b.deleted_at IS NULL
        GROUP BY b.announcement_id
        """)
    List<Object[]> sumReservedNetByAnnouncementIds(@Param("ids") java.util.Collection<UUID> ids,
                                                   @Param("statuses") List<String> statuses);

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

    /**
     * Colis d'un expéditeur, paginés côté SQL, du plus récent au plus ancien.
     *
     * <p>{@code statuses} n'est jamais vide : l'appelant y met l'ensemble voulu, et
     * à défaut tous les statuts de colis (discussions de prix exclues). Les colis
     * que l'expéditeur a retirés de sa liste ({@code deletedBySender}) n'y sont pas.
     */
    @Query("""
        SELECT b FROM BidEntity b
        WHERE b.senderId = :senderId
          AND b.deletedBySender = false
          AND b.status IN :statuses
          AND (:announcementId IS NULL OR b.announcementId = :announcementId)
        ORDER BY b.createdAt DESC
        """)
    Page<BidEntity> findBySenderIdFiltered(
            @Param("senderId") UUID senderId,
            @Param("statuses") Collection<BidStatus> statuses,
            @Param("announcementId") UUID announcementId,
            Pageable pageable);

    List<BidEntity> findBySenderIdAndStatusIn(UUID senderId, List<BidStatus> statuses);

    boolean existsBySenderIdAndStatusIn(UUID senderId, List<BidStatus> statuses);

    /**
     * Pendant voyageur de {@link #existsBySenderIdAndStatusIn} : le voyageur n'est pas
     * porte par le bid mais par l'annonce, d'ou la jointure. Sert au verrou pays
     * ({@code CountryLockService}) — un voyageur engage sur une livraison ne doit pas
     * plus pouvoir changer de pays que l'expediteur qui l'a paye.
     */
    @Query("""
        SELECT COUNT(b) > 0 FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :travelerId AND b.status IN :statuses
    """)
    boolean existsByAnnouncementTravelerIdAndStatusIn(
            @Param("travelerId") UUID travelerId,
            @Param("statuses") java.util.Collection<BidStatus> statuses);

    // For H-2 alert scheduler: ACCEPTED bids with handover starting in ≤ 2h, not yet alerted, not confirmed
    Optional<BidEntity> findByTrackingNumber(String trackingNumber);

    /**
     * Colis de l'expéditeur que l'assistant peut lui proposer : en cours ({@code active}), ou
     * clos ({@code ended}) depuis {@code since}. Les envois qu'il a retirés de sa liste n'y
     * sont pas : il ne les voit plus nulle part ailleurs.
     */
    @Query("""
        SELECT b FROM BidEntity b
        WHERE b.senderId = :userId AND b.deletedBySender = false
          AND (b.status IN :active OR (b.status IN :ended AND b.updatedAt >= :since))
        ORDER BY b.updatedAt DESC
        """)
    List<BidEntity> findAssistantParcelsOfSender(@Param("userId") UUID userId,
                                                 @Param("active") Collection<BidStatus> active,
                                                 @Param("ended") Collection<BidStatus> ended,
                                                 @Param("since") LocalDateTime since,
                                                 Pageable pageable);

    /** Pendant voyageur de {@link #findAssistantParcelsOfSender}, par la jointure sur le trajet. */
    @Query("""
        SELECT b FROM BidEntity b JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE a.travelerId = :userId AND b.deletedByTraveler = false
          AND (b.status IN :active OR (b.status IN :ended AND b.updatedAt >= :since))
        ORDER BY b.updatedAt DESC
        """)
    List<BidEntity> findAssistantParcelsOfTraveler(@Param("userId") UUID userId,
                                                   @Param("active") Collection<BidStatus> active,
                                                   @Param("ended") Collection<BidStatus> ended,
                                                   @Param("since") LocalDateTime since,
                                                   Pageable pageable);

    Optional<BidEntity> findByTrackingToken(String trackingToken);

    Optional<BidEntity> findByPaymentIntentId(String paymentIntentId);

    Optional<BidEntity> findByLinkedNegotiationThreadId(UUID linkedNegotiationThreadId);

    /**
     * Nettoyage des bids carte dont la fenêtre de paiement est dépassée
     * ({@code AwaitingPaymentCleanupScheduler}) : {@code excludedMethod} écarte en SQL le rail
     * qui partage le statut {@code AWAITING_PAYMENT} mais pas le PaymentIntent (mobile money,
     * expiré par {@code MobileMoneyPaymentDeadlineScheduler}) — jamais filtré en Java après
     * avoir chargé les lignes pour rien.
     */
    List<BidEntity> findByStatusAndPaymentMethodNotAndAwaitingPaymentExpiresAtBefore(
            BidStatus status, PaymentMethod excludedMethod, LocalDateTime threshold);

    /**
     * Promotion {@code AWAITING_PAYMENT → PAYMENT_ESCROWED} par écriture conditionnelle : 1 si le
     * bid attendait encore son paiement, 0 sinon. Sous PostgreSQL (READ COMMITTED), l'UPDATE attend
     * le verrou d'une annulation avant paiement en cours puis réévalue la condition sur la ligne
     * validée : un bid passé {@code CANCELLED} entre-temps n'est jamais promu (aucune écriture
     * « lue avant, écrite après » ne peut écraser l'annulation).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE BidEntity b SET b.status = com.yadony.api.matching.BidStatus.PAYMENT_ESCROWED, "
            + "b.awaitingPaymentExpiresAt = NULL WHERE b.id = :id "
            + "AND b.status = com.yadony.api.matching.BidStatus.AWAITING_PAYMENT")
    int promoteToEscrowedIfAwaitingPayment(@Param("id") UUID id);

    /** Statut lu en base (requête scalaire, jamais l'entité de la session). */
    @Query("SELECT b.status FROM BidEntity b WHERE b.id = :id")
    Optional<BidStatus> findStatusById(@Param("id") UUID id);

    /**
     * Identifiant du colis portant ce PaymentIntent, par requête scalaire (l'entité n'est pas
     * chargée avant son verrou — PROD_CHECKLIST 9.25).
     */
    @Query("SELECT b.id FROM BidEntity b WHERE b.paymentIntentId = :piId")
    Optional<UUID> findIdByPaymentIntentId(@Param("piId") String piId);

    /** Identifiant du colis repris par le checkout carte, lu sans charger l'entité avant son verrou. */
    @Query("SELECT b.id FROM BidEntity b WHERE b.senderId = :senderId AND b.announcementId = :announcementId "
            + "AND b.status = :status")
    Optional<UUID> findIdBySenderIdAndAnnouncementIdAndStatus(@Param("senderId") UUID senderId,
            @Param("announcementId") UUID announcementId, @Param("status") BidStatus status);

    /**
     * Abandon d'un bid carte resté AWAITING_PAYMENT au-delà de sa fenêtre
     * ({@code AwaitingPaymentCleanupScheduler}) : soft delete conditionnel. 0 si le bid a quitté
     * AWAITING_PAYMENT entre-temps (annulé avant paiement, promu) : il n'est jamais écrasé et le
     * code promo n'est pas rendu deux fois.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE BidEntity b SET b.deletedAt = :now WHERE b.id = :id "
            + "AND b.status = com.yadony.api.matching.BidStatus.AWAITING_PAYMENT AND b.deletedAt IS NULL")
    int softDeleteIfAwaitingPayment(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * Expiration des bids mobile money en attente de paiement (MobileMoneyPaymentDeadlineScheduler).
     * Identifiants seulement : le scheduler relit chaque bid sous verrou dans sa propre
     * transaction, hydrater l'entité complète ici (dont le numéro payeur chiffré) serait jeté.
     *
     * <p>Bornée par {@code pageable} — même motif que
     * {@code PawapayOperationRepository#findOpenForReconciliation} : un incident laissant
     * s'accumuler des bids en souffrance ne doit jamais faire durer un passage du scheduler
     * indéfiniment, sur l'unique pool de scheduling partagé par tous les crons du dépôt.
     */
    @Query("SELECT b.id FROM BidEntity b WHERE b.status = :status AND b.paymentMethod = :method "
            + "AND b.awaitingPaymentExpiresAt < :threshold")
    List<UUID> findIdsByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
            @Param("status") BidStatus status, @Param("method") PaymentMethod paymentMethod,
            @Param("threshold") LocalDateTime threshold, Pageable pageable);

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

    // No-show detection: ACCEPTED bids with handoverDeadline before :cutoff, no DEPART scan, not yet marked NO_SHOW.
    // handoverDeadline est stockée en UTC : :cutoff (now - 1 h, UTC) est une borne exacte
    // (YADONY-FLUTTER-PROD-1R).
    // Exclut les bids où le voyageur a déclaré l'expéditeur absent (remise) : tant que
    // l'expéditeur a ses 24 h pour confirmer/contester, ou qu'un litige est ouvert, le bid
    // reste ACCEPTED et ce flux suit son propre cours. Sans cette exclusion, l'expéditeur
    // absent faisait pénaliser le voyageur et se faisait rembourser intégralement.
    // RESOLVED (déclaration rejetée par l'admin) n'est pas exclu : le bid reprend son cours.
    @Query("SELECT b FROM BidEntity b WHERE b.status = 'ACCEPTED' " +
           "AND b.handoverDeadline IS NOT NULL " +
           "AND b.handoverDeadline < :cutoff " +
           "AND b.noShowAt IS NULL " +
           "AND b.deletedAt IS NULL " +
           "AND NOT EXISTS (SELECT t FROM TrackingEventEntity t WHERE t.bidId = b.id AND t.eventType = 'DEPART') " +
           "AND NOT EXISTS (SELECT c FROM CancellationEntity c WHERE c.bidId = b.id " +
           "AND c.scope = com.yadony.api.cancellation.CancellationScope.HANDOVER " +
           "AND c.reason = 'SENDER_NO_SHOW' " +
           "AND c.noShowStatus IN (com.yadony.api.cancellation.CancellationStatus.PENDING_CONFIRMATION, " +
           "com.yadony.api.cancellation.CancellationStatus.CONTESTED, " +
           "com.yadony.api.cancellation.CancellationStatus.CONFIRMED))")
    List<BidEntity> findNoShowBids(@Param("cutoff") LocalDateTime cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BidEntity b WHERE b.id = :id AND b.deletedAt IS NULL")
    Optional<BidEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Identifiant d'un colis et fil de négociation dont il est issu (nul pour un colis classique). */
    interface BidThreadLink {
        UUID getId();
        UUID getLinkedNegotiationThreadId();
    }

    /**
     * Colis (non supprimés) dont l'id est dans {@code ids} ou issus d'un fil de {@code threadIds},
     * en une requête : lien « voir le colis » d'une page d'historique du solde (FLUTTER-QP).
     * Les deux listes doivent être non vides (l'appelant passe un id sentinelle sinon).
     */
    @Query("SELECT b.id AS id, b.linkedNegotiationThreadId AS linkedNegotiationThreadId FROM BidEntity b "
            + "WHERE b.deletedAt IS NULL AND (b.id IN :ids OR b.linkedNegotiationThreadId IN :threadIds)")
    List<BidThreadLink> findBidThreadLinks(@Param("ids") java.util.Collection<UUID> ids,
                                           @Param("threadIds") java.util.Collection<UUID> threadIds);

    /**
     * Verrou de ligne du colis, tenu jusqu'à la fin de la transaction, par un UPDATE neutre
     * (exécutable aussi sous H2, contrairement à {@code SELECT … FOR NO KEY UPDATE}). À appeler
     * AVANT de charger le colis : la lecture qui suit voit alors le dernier statut commité.
     * Sérialise livraison, annulations (expéditeur, voyageur, admin) et libération forcée.
     *
     * @return 1 si le colis existe, 0 sinon
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE BidEntity b SET b.status = b.status WHERE b.id = :id")
    int lockForUpdate(@Param("id") UUID id);

    /**
     * Colis déjà remis au voyageur (HANDED_OVER, IN_TRANSIT, ARRIVED) d'un trajet, verrouillés :
     * l'annulation du trajet leur pose un code de retour (FLUTTER-FH). Le verrou évite qu'un scan
     * concurrent (livraison, arrivée) ne croise la procédure de retour.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BidEntity b WHERE b.announcementId = :announcementId AND b.deletedAt IS NULL "
            + "AND b.status IN (com.yadony.api.matching.BidStatus.HANDED_OVER, "
            + "com.yadony.api.matching.BidStatus.IN_TRANSIT, com.yadony.api.matching.BidStatus.ARRIVED) "
            + "ORDER BY b.createdAt ASC")
    List<BidEntity> findHandedOverByAnnouncementIdForUpdate(@Param("announcementId") UUID announcementId);

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
    // (en tant qu'expéditeur OU voyageur via la jointure avec announcements).
    // windowStart : borne de la fenêtre de notation, sur la même référence (updated_at) que
    // RatingService. Sans elle, la pop-up proposait un envoi déjà hors délai (FLUTTER-F1).
    @Query(nativeQuery = true, value = """
        SELECT b.* FROM bids b
        JOIN announcements a ON b.announcement_id = a.id
        WHERE b.status = 'COMPLETED'
          AND b.deleted_at IS NULL
          AND b.updated_at >= :windowStart
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
    Optional<BidEntity> findPendingRatingForUser(@Param("userId") UUID userId,
                                                @Param("windowStart") LocalDateTime windowStart);

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

    List<BidEntity> findByCommissionStatusAndUpdatedAtBefore(
            CommissionStatus commissionStatus, LocalDateTime before);

    /**
     * True if there is at least one active (in-flight) transaction between two users,
     * in either direction (sender↔traveler). Used to keep a blocked counterparty
     * visible until the delivery is over.
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

    /**
     * Batch counterpart of {@link #hasActiveTransactionBetween}: among {@code candidateIds},
     * the users still in an active transaction with {@code userId}. Lets the block filter
     * spare those counterparties without one query per candidate.
     */
    @Query("""
        SELECT CASE WHEN b.senderId = :userId THEN a.travelerId ELSE b.senderId END
        FROM BidEntity b
        JOIN AnnouncementEntity a ON a.id = b.announcementId
        WHERE b.status IN :activeStatuses AND (
              (b.senderId = :userId AND a.travelerId IN :candidateIds)
           OR (b.senderId IN :candidateIds AND a.travelerId = :userId))
        """)
    List<UUID> findActiveTransactionCounterparties(
            @Param("userId") UUID userId,
            @Param("candidateIds") List<UUID> candidateIds,
            @Param("activeStatuses") List<BidStatus> activeStatuses);

    /**
     * Liste des colis de l'admin : vue rapide, recherche par jetons et identifiants, filtres et tri
     * selon la vue. Fragments et paramètres décrits dans {@link AdminBidSql} ; appeler de préférence
     * {@link #searchAdmin(AdminBidQuery, Pageable)} avec un {@code Pageable} non trié.
     */
    @Query(value = "SELECT b.* FROM" + AdminBidSql.FROM_JOIN
            + " WHERE " + AdminBidSql.FILTER_PREDICATE
            + " AND " + AdminBidSql.VIEW_PREDICATE
            + " AND " + AdminBidSql.SEARCH_PREDICATE
            + " ORDER BY " + AdminBidSql.ORDER_BY,
        countQuery = "SELECT COUNT(*) FROM" + AdminBidSql.FROM_JOIN
            + " WHERE " + AdminBidSql.FILTER_PREDICATE
            + " AND " + AdminBidSql.VIEW_PREDICATE
            + " AND " + AdminBidSql.SEARCH_PREDICATE,
        nativeQuery = true)
    Page<BidEntity> searchAdmin(
            @Param("view") String view,
            @Param("status") String status,
            @Param("announcementId") String announcementId,
            @Param("t1") String t1,
            @Param("t2") String t2,
            @Param("t3") String t3,
            @Param("t4") String t4,
            @Param("idTerm") String idTerm,
            @Param("scope") String scope,
            @Param("phoneSuffix") String phoneSuffix,
            @Param("paymentMethod") String paymentMethod,
            @Param("content") String content,
            @Param("alertRank") Integer alertRank,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("now") LocalDateTime now,
            @Param("lateBefore") LocalDate lateBefore,
            Pageable pageable);

    /** {@link #searchAdmin} avec les paramètres déjà normalisés par {@link AdminBidQuery#of}. */
    default Page<BidEntity> searchAdmin(AdminBidQuery query, Pageable pageable) {
        return searchAdmin(query.view().name(), query.status(), query.announcementId(),
                query.token(0), query.token(1), query.token(2), query.token(3),
                query.idTerm() != null ? query.idTerm().toString() : null,
                query.scope().name(), query.phoneSuffix(), query.paymentMethod(), query.content(),
                query.alertRank(), query.from(), query.to(), query.now(), query.lateBefore(), pageable);
    }

    /**
     * Nombres par vue et par alerte, avec la recherche et les filtres de {@link #searchAdmin} (sauf
     * vue, statut et alerte, qui ne s'appliquent pas) : une seule requête, mêmes fragments SQL.
     */
    @Query(value = AdminBidSql.COUNTS_SELECT + "FROM" + AdminBidSql.FROM_JOIN
            + " WHERE " + AdminBidSql.FILTER_PREDICATE
            + " AND " + AdminBidSql.SEARCH_PREDICATE,
        nativeQuery = true)
    AdminCountsRow countAdmin(
            @Param("status") String status,
            @Param("announcementId") String announcementId,
            @Param("t1") String t1,
            @Param("t2") String t2,
            @Param("t3") String t3,
            @Param("t4") String t4,
            @Param("idTerm") String idTerm,
            @Param("scope") String scope,
            @Param("phoneSuffix") String phoneSuffix,
            @Param("paymentMethod") String paymentMethod,
            @Param("content") String content,
            @Param("alertRank") Integer alertRank,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("now") LocalDateTime now,
            @Param("lateBefore") LocalDate lateBefore);

    /** {@link #countAdmin} avec les paramètres normalisés par {@link AdminBidQuery#of} ; vue, statut et alerte ignorés. */
    default AdminCountsRow countAdmin(AdminBidQuery query) {
        return countAdmin(null, query.announcementId(),
                query.token(0), query.token(1), query.token(2), query.token(3),
                query.idTerm() != null ? query.idTerm().toString() : null,
                query.scope().name(), query.phoneSuffix(), query.paymentMethod(), query.content(),
                null, query.from(), query.to(), query.now(), query.lateBefore());
    }

    /** Projection de {@link #countAdmin} : une ligne, jamais nulle (COALESCE). */
    interface AdminCountsRow {
        Long getTodo();

        Long getActive();

        Long getRecent();

        Long getCompleted();

        Long getClosed();

        Long getAll();

        Long getDisputeOpen();

        Long getNoShow();

        Long getPaymentPending();

        Long getLate();
    }

    /**
     * Rang d'alerte ({@link AdminBidSql#ALERT_RANK}) des colis donnés. Seuls les colis en alerte ont
     * une ligne : un identifiant absent du résultat n'a aucune alerte (rang 0). Une liste vide ne
     * part pas en base ({@code IN ()} n'est pas du SQL valide).
     */
    default List<AlertRankRow> alertRanks(Collection<UUID> ids, LocalDateTime now, LocalDate lateBefore) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return alertRanksNative(ids, now, lateBefore);
    }

    /** Requête de {@link #alertRanks} ; {@code ids} non vide. */
    @Query(value = "SELECT CAST(b.id AS VARCHAR) AS \"bidIdText\", " + AdminBidSql.ALERT_RANK + " AS \"rank\""
            + " FROM" + AdminBidSql.FROM_JOIN
            + " WHERE b.id IN (:ids) AND " + AdminBidSql.ALERT_RANK + " > 0",
        nativeQuery = true)
    List<AlertRankRow> alertRanksNative(@Param("ids") Collection<UUID> ids,
                                        @Param("now") LocalDateTime now,
                                        @Param("lateBefore") LocalDate lateBefore);

    /** Projection de {@link #alertRanks} : rang de 1 à 4, voir {@code AdminBidAlert.ofRank}. */
    interface AlertRankRow {
        /** Identifiant en texte : H2 rend un UUID natif en octets, que la projection ne sait pas convertir. */
        String getBidIdText();

        Integer getRank();

        default UUID getBidId() {
            return UUID.fromString(getBidIdText());
        }
    }

    // --- Suggestions de recherche de l'admin (GET /admin/bids/suggest) : voir AdminBidSql.SUGGEST_* ---

    @Query(value = AdminBidSql.SUGGEST_SENDERS, nativeQuery = true)
    List<PersonCountRow> suggestSenders(@Param("t1") String t1, @Param("t2") String t2,
                                        @Param("t3") String t3, @Param("t4") String t4);

    @Query(value = AdminBidSql.SUGGEST_TRAVELERS, nativeQuery = true)
    List<PersonCountRow> suggestTravelers(@Param("t1") String t1, @Param("t2") String t2,
                                          @Param("t3") String t3, @Param("t4") String t4);

    @Query(value = AdminBidSql.SUGGEST_RECIPIENTS, nativeQuery = true)
    List<RecipientCountRow> suggestRecipients(@Param("t1") String t1, @Param("t2") String t2,
                                              @Param("t3") String t3, @Param("t4") String t4);

    @Query(value = AdminBidSql.SUGGEST_TRIPS, nativeQuery = true)
    List<TripCountRow> suggestTrips(@Param("t1") String t1, @Param("t2") String t2,
                                    @Param("t3") String t3, @Param("t4") String t4);

    @Query(value = AdminBidSql.SUGGEST_PARCELS, nativeQuery = true)
    List<ParcelSuggestRow> suggestParcels(@Param("t1") String t1, @Param("t2") String t2,
                                          @Param("t3") String t3, @Param("t4") String t4);

    /** Utilisateur et nombre de ses colis ; identifiant en texte (H2 rend un UUID natif en octets). */
    interface PersonCountRow {
        String getIdText();

        Long getTotal();

        default UUID getId() {
            return UUID.fromString(getIdText());
        }
    }

    interface RecipientCountRow {
        String getName();

        Long getTotal();
    }

    interface TripCountRow {
        String getDepartureCity();

        String getArrivalCity();

        Long getTotal();
    }

    interface ParcelSuggestRow {
        String getBidIdText();

        String getTrackingNumber();

        String getStatus();

        String getContent();

        default UUID getBidId() {
            return UUID.fromString(getBidIdText());
        }
    }

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

    /**
     * « Discussions de prix » de l'utilisateur, expéditeur ou voyageur du trajet : tous
     * ses fils ni rangés ni retirés de son côté, QUEL QUE SOIT leur statut.
     *
     * <p>Un fil se reconnaît comme dans {@link #findArchivedNegotiationsForUser} et
     * {@code BidNegotiationService#loadNegotiation} : statut de discussion (NEGOTIATING /
     * NEGOTIATION_CLOSED) ou prix d'accord, posé seulement par la négociation. Un fil
     * conclu puis réglé (ACCEPTED, HANDED_OVER, DELIVERED…) ou clos reste listé : seul
     * l'utilisateur range ou retire une discussion, jamais le serveur (FLUTTER-HM — le
     * fil disparaissait de toutes les puces dès le paiement, alors que seules ces
     * discussions terminées peuvent être rangées ou retirées). Un bid matérialisé depuis un
     * fil de demande d'envoi ({@code linkedNegotiationThreadId}) porte aussi un brut (total
     * payé d'un fil à grille) sans être une discussion de prix : il n'est jamais listé.
     */
    @Query("""
        SELECT b FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE (b.status IN (com.yadony.api.matching.BidStatus.NEGOTIATING,
                            com.yadony.api.matching.BidStatus.NEGOTIATION_CLOSED)
               OR (b.negotiatedGrossEur IS NOT NULL AND b.linkedNegotiationThreadId IS NULL))
          AND ((b.senderId = :userId
                AND b.negotiationSenderHiddenAt IS NULL AND b.negotiationSenderArchivedAt IS NULL)
               OR (a.travelerId = :userId
                AND b.negotiationTravelerHiddenAt IS NULL AND b.negotiationTravelerArchivedAt IS NULL))
          AND b.deletedAt IS NULL
        ORDER BY b.updatedAt DESC
    """)
    List<BidEntity> findNegotiationsForUser(@Param("userId") UUID userId);

    /**
     * Filtre « Archivées » des discussions de prix : rangées par l'utilisateur, pas
     * retirées. Une discussion se reconnaît à son statut (NEGOTIATING /
     * NEGOTIATION_CLOSED) ou à son prix d'accord, posé seulement par la négociation.
     */
    @Query("""
        SELECT b FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE (b.status IN (com.yadony.api.matching.BidStatus.NEGOTIATING,
                            com.yadony.api.matching.BidStatus.NEGOTIATION_CLOSED)
               OR (b.negotiatedGrossEur IS NOT NULL AND b.linkedNegotiationThreadId IS NULL))
          AND ((b.senderId = :userId
                AND b.negotiationSenderHiddenAt IS NULL AND b.negotiationSenderArchivedAt IS NOT NULL)
               OR (a.travelerId = :userId
                AND b.negotiationTravelerHiddenAt IS NULL AND b.negotiationTravelerArchivedAt IS NOT NULL))
          AND b.deletedAt IS NULL
        ORDER BY b.updatedAt DESC
    """)
    List<BidEntity> findArchivedNegotiationsForUser(@Param("userId") UUID userId);

    // Rangement / retrait d'une discussion par un participant (V293). Mises à jour
    // ciblées : updated_at du bid ne bouge pas (il ordonne d'autres listes de colis).

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE BidEntity b SET b.negotiationSenderArchivedAt = :at WHERE b.id = :id")
    int updateNegotiationSenderArchivedAt(@Param("id") UUID id, @Param("at") LocalDateTime at);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE BidEntity b SET b.negotiationTravelerArchivedAt = :at WHERE b.id = :id")
    int updateNegotiationTravelerArchivedAt(@Param("id") UUID id, @Param("at") LocalDateTime at);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE BidEntity b SET b.negotiationSenderHiddenAt = :at WHERE b.id = :id")
    int updateNegotiationSenderHiddenAt(@Param("id") UUID id, @Param("at") LocalDateTime at);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE BidEntity b SET b.negotiationTravelerHiddenAt = :at WHERE b.id = :id")
    int updateNegotiationTravelerHiddenAt(@Param("id") UUID id, @Param("at") LocalDateTime at);

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

    /**
     * Candidats à l'expiration « date limite de dépôt passée » (FLUTTER-GA) : identifiants
     * des demandes encore non engagées dont le trajet a une date limite au plus tard à
     * {@code upperBound}.
     *
     * <p>La date limite est stockée en UTC et {@code upperBound} vaut l'instant présent en UTC
     * (YADONY-FLUTTER-PROD-1R). La décision est reconfirmée trajet par trajet, via
     * {@link AnnouncementEntity#isHandoverDeadlinePassed}.
     * Un trajet sans date limite n'est jamais candidat.
     */
    @Query("""
        SELECT b.id FROM BidEntity b
        JOIN AnnouncementEntity a ON b.announcementId = a.id
        WHERE b.status IN :statuses
          AND b.deletedAt IS NULL
          AND a.handoverDeadline IS NOT NULL
          AND a.handoverDeadline <= :upperBound
    """)
    List<UUID> findIdsForHandoverDeadlineExpiry(
            @Param("statuses") Collection<BidStatus> statuses,
            @Param("upperBound") LocalDateTime upperBound);

    /**
     * L'autre partie d'une offre, sans charger l'entité.
     *
     * <p>Le voyageur n'est pas sur le bid mais sur l'annonce : sans jointure il faudrait une
     * requête par offre pour l'obtenir.
     */
    interface BidCounterparty {
        UUID getBidId();
        UUID getCounterpartyId();
    }

    /** Offres où l'utilisateur est expéditeur — la contrepartie est le voyageur. */
    @Query("""
            SELECT b.id AS bidId, a.travelerId AS counterpartyId
            FROM BidEntity b JOIN AnnouncementEntity a ON b.announcementId = a.id
            WHERE b.senderId = :senderId AND b.status IN :statuses
            """)
    List<BidCounterparty> findTravelerCounterpartiesForSender(
            @Param("senderId") UUID senderId,
            @Param("statuses") Collection<BidStatus> statuses);

    /** Offres portant sur les annonces de l'utilisateur — la contrepartie est l'expéditeur. */
    @Query("""
            SELECT b.id AS bidId, b.senderId AS counterpartyId
            FROM BidEntity b JOIN AnnouncementEntity a ON b.announcementId = a.id
            WHERE a.travelerId = :travelerId AND b.status IN :statuses
            """)
    List<BidCounterparty> findSenderCounterpartiesForTraveler(
            @Param("travelerId") UUID travelerId,
            @Param("statuses") Collection<BidStatus> statuses);

    /** Commissions de colis prélevées par carte et touchées depuis {@code since} (rapprochement Stripe). */
    @Query("SELECT b FROM BidEntity b WHERE b.commissionChargedVia = :via "
            + "AND b.commissionPaymentIntentId IS NOT NULL AND b.updatedAt >= :since")
    List<BidEntity> findCardCommissionsUpdatedSince(
            @Param("via") com.yadony.api.payments.cash.CommissionChargedVia via,
            @Param("since") java.time.LocalDateTime since);

    /**
     * Rapprochement R2 : photos de refus de colis, saisies libres du client (n'importe quel
     * préfixe). Requête native : les bids supprimés logiquement gardent leur photo.
     */
    @Query(value = "SELECT refusal_photo_url FROM bids WHERE refusal_photo_url LIKE CONCAT(:prefix, '%') "
            + "OR refusal_photo_url LIKE CONCAT('%/', :prefix, '%')", nativeQuery = true)
    List<String> findRefusalPhotoValuesMatchingIncludingDeleted(@Param("prefix") String prefix);

    /**
     * Pose {@code delivery_counted} de façon atomique : seule la première transaction qui
     * passe de false à true reçoit 1, toute autre (événement rejoué, confirmation
     * concurrente) reçoit 0 et ne compte pas le colis une seconde fois.
     * Contexte vidé après coup : le bid déjà chargé (drapeau périmé) n'est jamais réécrit.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BidEntity b set b.deliveryCounted = true where b.id = :id and b.deliveryCounted = false")
    int markDeliveryCounted(@Param("id") UUID id);
}
