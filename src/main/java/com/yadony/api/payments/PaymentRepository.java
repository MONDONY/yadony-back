package com.yadony.api.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.yadony.api.matching.dto.AnnouncementRevenueRow;
import com.yadony.api.payments.dto.MobileMoneyCommissionMonthRow;
import com.yadony.api.payments.dto.PaymentVolumeRow;
import com.yadony.api.payments.dto.MobileMoneyCommissionRow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByBidId(UUID bidId);

    /** Paiements d'une page de bids en une requête (file admin des no-shows). */
    List<PaymentEntity> findByBidIdIn(Collection<UUID> bidIds);

    Optional<PaymentEntity> findByNegotiationThreadId(UUID negotiationThreadId);

    /**
     * Paiement du fil de négociation dont le bid a été matérialisé : ce paiement est
     * rattaché au fil ({@code bid_id} NULL), le bid étant créé APRÈS le paiement
     * ({@code ThreadAcceptedBidListener}). Vide pour un bid classique (pas de fil lié).
     * Préférer {@link #findForBid}.
     */
    @Query("""
            SELECT p FROM PaymentEntity p
            WHERE p.bidId IS NULL
              AND p.negotiationThreadId = (SELECT b.linkedNegotiationThreadId
                                           FROM com.yadony.api.matching.BidEntity b
                                           WHERE b.id = :bidId)
            """)
    Optional<PaymentEntity> findLinkedNegotiationPaymentOfBid(@Param("bidId") UUID bidId);

    /**
     * Résolution unique du paiement d'un bid : celui rattaché au bid, sinon celui du fil de
     * négociation lié (bid matérialisé). Tout chemin qui rembourse, libère ou affiche le
     * paiement d'un bid doit passer par ici — un {@code findByBidId} seul ignore les bids
     * négociés et laisse l'expéditeur sans remboursement.
     */
    default Optional<PaymentEntity> findForBid(UUID bidId) {
        return findByBidId(bidId).or(() -> findLinkedNegotiationPaymentOfBid(bidId));
    }

    Optional<PaymentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    Optional<PaymentEntity> findByStripeChargeId(String chargeId);

    List<PaymentEntity> findByStatus(PaymentStatus status);

    /** Story 6.5 — Find all payments in a given status whose escrow started before the given threshold. */
    List<PaymentEntity> findByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime threshold);

    /**
     * Paiements carte restés PENDING avec un PaymentIntent, créés dans la fenêtre
     * {@code ]newerThan, olderThan[}, <b>plus récents d'abord</b> : candidats à l'auto-réparation
     * ({@link PendingCardPaymentAutoHealJob}). Le plus récent est le plus susceptible d'être un vrai
     * blocage à rattraper avant l'expiration de l'autorisation ; un stock de checkouts abandonnés
     * plus anciens ne peut donc jamais l'affamer. Paginé par le job.
     */
    @Query("""
            SELECT p.id FROM PaymentEntity p
             WHERE p.status = com.yadony.api.payments.PaymentStatus.PENDING
               AND p.rail = com.yadony.api.payments.PaymentRail.STRIPE
               AND p.stripePaymentIntentId IS NOT NULL
               AND p.createdAt < :olderThan
               AND p.createdAt > :newerThan
             ORDER BY p.createdAt DESC, p.id
            """)
    List<UUID> findPendingCardPaymentIds(@Param("olderThan") LocalDateTime olderThan,
                                         @Param("newerThan") LocalDateTime newerThan,
                                         org.springframework.data.domain.Pageable page);

    /**
     * Séquestres carte jamais capturés alors que le modèle actuel les capture déjà (staging,
     * 07/10/2026 : 5 paiements ESCROW sans {@code captured_at}, l'autorisation expirant à J+7) :
     * paiement de négociation (capture au passage en séquestre, #472) ou colis classique engagé
     * (capture à l'acceptation, {@code BidAcceptedEventListener}). Créés avant {@code olderThan}
     * (les traitements normaux ont eu le temps de capturer).
     *
     * <p>Exclus : legacy (capture à la livraison), litige bancaire, remboursement partiel, versement
     * retenu ; colis annulé ou terminé (seuls les statuts engagés sont repris) ; colis portant une
     * procédure d'annulation ou d'absence non résolue ; fil de négociation éteint, ou dont le colis
     * rattaché n'est pas engagé. Plus récents d'abord ; paginé par le job.
     */
    @Query("""
            SELECT p.id FROM PaymentEntity p
             WHERE p.status = com.yadony.api.payments.PaymentStatus.ESCROW
               AND p.rail = com.yadony.api.payments.PaymentRail.STRIPE
               AND p.stripePaymentIntentId IS NOT NULL
               AND p.capturedAt IS NULL
               AND p.legacyDestinationCharge = false
               AND p.disputed = false
               AND (p.refundedAmount IS NULL OR p.refundedAmount = 0)
               AND p.payoutHeldAt IS NULL
               AND p.createdAt < :olderThan
               AND (
                    (p.bidId IS NOT NULL AND EXISTS (
                        SELECT 1 FROM com.yadony.api.matching.BidEntity b
                         WHERE b.id = p.bidId AND b.status IN :engaged))
                 OR (p.bidId IS NULL AND p.negotiationThreadId IS NOT NULL
                     AND EXISTS (SELECT 1 FROM com.yadony.api.requests.entity.NegotiationThreadEntity t
                                  WHERE t.id = p.negotiationThreadId AND t.status NOT IN :deadThreads)
                     AND NOT EXISTS (SELECT 1 FROM com.yadony.api.matching.BidEntity b2
                                      WHERE b2.linkedNegotiationThreadId = p.negotiationThreadId
                                        AND b2.status NOT IN :engaged))
               )
               AND NOT EXISTS (SELECT 1 FROM com.yadony.api.cancellation.CancellationEntity c, com.yadony.api.matching.BidEntity b3
                                WHERE c.bidId = b3.id
                                  AND (b3.id = p.bidId OR (p.bidId IS NULL AND b3.linkedNegotiationThreadId = p.negotiationThreadId))
                                  AND c.noShowStatus <> com.yadony.api.cancellation.CancellationStatus.RESOLVED)
             ORDER BY p.createdAt DESC, p.id
            """)
    List<UUID> findUncapturedDueEscrowIds(@Param("olderThan") LocalDateTime olderThan,
                                          @Param("engaged") java.util.Collection<com.yadony.api.matching.BidStatus> engaged,
                                          @Param("deadThreads") java.util.Collection<com.yadony.api.requests.entity.NegotiationThreadStatus> deadThreads,
                                          org.springframework.data.domain.Pageable page);

    /**
     * Séquestres carte non versés dont le colis est livré ({@code COMPLETED}, par {@code bid_id} ou
     * par le colis rattaché au fil de négociation) : la livraison ou le rattrapage n'ont pas abouti.
     * Dernière activité (livraison ou passage en séquestre) entre {@code windowStart} et
     * {@code graceBefore} : le versement normal a eu le temps de partir, et tout essai précédent
     * date de moins de 24 h (clé d'idempotence Stripe {@code transfer-<id>} encore valable).
     * Exclus : litige, remboursement partiel, versement retenu — leur garde a déjà alerté et un
     * nouvel essai n'y changerait rien. Plus récents d'abord ; un paiement peut sortir deux fois si
     * deux colis sont rattachés au même fil (dédoublonné par l'appelant).
     */
    @Query("""
            SELECT p.id FROM PaymentEntity p, com.yadony.api.matching.BidEntity b
             WHERE p.status = com.yadony.api.payments.PaymentStatus.ESCROW
               AND p.rail = com.yadony.api.payments.PaymentRail.STRIPE
               AND p.disputed = false
               AND (p.refundedAmount IS NULL OR p.refundedAmount = 0)
               AND p.payoutHeldAt IS NULL
               AND b.status = com.yadony.api.matching.BidStatus.COMPLETED
               AND ((p.bidId IS NOT NULL AND b.id = p.bidId)
                    OR (p.bidId IS NULL AND p.negotiationThreadId IS NOT NULL
                        AND b.linkedNegotiationThreadId = p.negotiationThreadId))
               AND COALESCE(b.deliveredAt, b.updatedAt) < :graceBefore
               AND p.updatedAt < :graceBefore
               AND (COALESCE(b.deliveredAt, b.updatedAt) > :windowStart OR p.updatedAt > :windowStart)
             ORDER BY p.updatedAt DESC, p.id
            """)
    List<UUID> findDeliveredUnreleasedEscrowIds(@Param("graceBefore") LocalDateTime graceBefore,
                                                @Param("windowStart") LocalDateTime windowStart,
                                                org.springframework.data.domain.Pageable page);

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
     * Claim du versement automatique (livraison, colis non réclamé, rattrapage, job) : ESCROW →
     * RELEASED seulement si, <b>dans le même UPDATE</b>, aucun litige bancaire, aucun remboursement
     * partiel et aucune retenue ne sont posés. Les gardes lues avant le claim ne sont qu'un
     * pré-filtre (alerte, audit) : un chargeback ou un remboursement arrivé entre la lecture et le
     * claim fait répondre 0 ici, rien n'est versé. La garde d'un litige admin est revérifiée après
     * ce claim (le verrou de ligne posé ici sérialise l'ouverture du litige, qui verrouille la même
     * ligne avant d'écrire).
     */
    @Modifying
    @Query("""
            UPDATE PaymentEntity p SET p.status = com.yadony.api.payments.PaymentStatus.RELEASED,
                   p.escrowReleasedAt = :releasedAt
             WHERE p.id = :id AND p.status = com.yadony.api.payments.PaymentStatus.ESCROW
               AND p.disputed = false
               AND (p.refundedAmount IS NULL OR p.refundedAmount = 0)
               AND p.payoutHeldAt IS NULL
            """)
    int markReleasedIfEscrowAndUnguarded(@Param("id") UUID id, @Param("releasedAt") LocalDateTime releasedAt);

    /** Annule un claim RELEASED posé dans la transaction courante (garde revérifiée après le claim). */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = com.yadony.api.payments.PaymentStatus.ESCROW, p.escrowReleasedAt = NULL "
            + "WHERE p.id = :id AND p.status = com.yadony.api.payments.PaymentStatus.RELEASED")
    int revertReleaseClaim(@Param("id") UUID id);

    /**
     * Claim d'un partage admin (FLUTTER-E2) : ESCROW → {@code status} (RELEASED si le voyageur
     * reçoit une part, REFUNDED sinon) et {@code refunded_amount} porté d'avance à sa valeur
     * finale. Stripe renverra la même valeur absolue par {@code charge.refunded} : le webhook la
     * voit déjà enregistrée et ne lève pas d'alerte « remboursement après versement ». 0 si le
     * paiement a quitté le séquestre entre-temps.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = :status, p.escrowReleasedAt = :releasedAt, "
            + "p.refundedAmount = :refunded WHERE p.id = :id AND p.status = 'ESCROW'")
    int claimForSplit(@Param("id") UUID id, @Param("status") PaymentStatus status,
                      @Param("releasedAt") LocalDateTime releasedAt, @Param("refunded") java.math.BigDecimal refunded);

    /**
     * Atomic capture-once CAS guard. Returns 1 if the row was updated (first capture),
     * 0 if already captured or not in ESCROW status.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.capturedAt = :now WHERE p.id = :id AND p.capturedAt IS NULL AND p.status = com.yadony.api.payments.PaymentStatus.ESCROW")
    int markCapturedIfEscrow(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Pose le verrou de ligne d'un paiement encore ESCROW, sans rien changer (UPDATE neutre) :
     * 1 si le paiement est toujours en séquestre (verrou tenu jusqu'à la fin de la transaction,
     * un remboursement ou un versement concurrent attend), 0 s'il l'a quitté. Sert à
     * {@code EscrowCaptureService} quand {@link #markCapturedIfEscrow} répond 0, pour ne jamais
     * capturer un paiement déjà remboursé, versé ou annulé. UPDATE plutôt que
     * {@code SELECT … FOR NO KEY UPDATE} : exécutable aussi sous H2.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.capturedAt = p.capturedAt WHERE p.id = :id "
            + "AND p.status = com.yadony.api.payments.PaymentStatus.ESCROW")
    int lockIfEscrow(@Param("id") UUID id);

    /** Statut lu en base (requête, pas le cache de la session). */
    @Query("SELECT p.status FROM PaymentEntity p WHERE p.id = :id")
    Optional<PaymentStatus> findStatusById(@Param("id") UUID id);

    /**
     * Enregistre le charge Stripe s'il manque, par une écriture ciblée : jamais un {@code save}
     * d'entité chargée avant un appel Stripe ({@code PaymentEntity} n'a ni {@code @DynamicUpdate}
     * ni {@code @Version}, un flush réécrirait statut, remboursé et litige depuis un état périmé).
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.stripeChargeId = :chargeId WHERE p.id = :id AND p.stripeChargeId IS NULL")
    int setStripeChargeIdIfMissing(@Param("id") UUID id, @Param("chargeId") String chargeId);

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
     * Jumeau de {@link #findByBidIdForUpdate} pour le paiement d'un fil de négociation
     * (rail mobile money) : même verrou {@code PESSIMISTIC_WRITE} (FOR NO KEY UPDATE), même
     * compatibilité avec le KEY SHARE de l'INSERT d'une opération pawaPay en REQUIRES_NEW.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentEntity p WHERE p.negotiationThreadId = :threadId")
    Optional<PaymentEntity> findByNegotiationThreadIdForUpdate(@Param("threadId") UUID threadId);

    /**
     * Vrai si le paiement est keyé sur un fil de négociation (bid_id NULL, CHECK exclusif V62).
     * Requête scalaire, jamais d'entité en cache : l'aiguillage précède un claim bulk
     * ({@link #markEscrowIfPending}, {@code @Modifying} sans {@code clearAutomatically}), et un
     * {@code findById} ici laisserait dans le contexte de persistance un {@code PaymentEntity}
     * dont le {@code findById} suivant, après le claim, rendrait le snapshot périmé (statut
     * CANCELLED commité entre les deux invisible, remboursement avalé). Vide si le paiement
     * n'existe pas.
     */
    @Query("select (p.negotiationThreadId is not null) from PaymentEntity p where p.id = :id")
    Optional<Boolean> isThreadScoped(@Param("id") UUID id);

    /**
     * Séquestre mobile money : PENDING → ESCROW, une seule fois. 0 = déjà en ESCROW (rejeu) ou
     * déjà CANCELLED (deadline passée pendant la saisie du PIN — l'appelant rembourse alors).
     * Le deposit qui a financé le séquestre se retrouve par {@code pawapay_operations.payment_id},
     * jamais par une colonne de {@code payments}.
     *
     * <p>Comme tout claim bulk de cette interface ({@code @Modifying} sans
     * {@code clearAutomatically}) : une entité {@code PaymentEntity} chargée AVANT l'appel garde
     * son snapshot périmé en mémoire — ne jamais lui appliquer de setter ensuite (voir
     * {@code PaymentRepositoryMobileMoneyTest}), relire par {@code entityManager.refresh} si une
     * réponse doit refléter la ligne réelle.
     */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = 'ESCROW', p.capturedAt = :now "
            + "WHERE p.id = :id AND p.status = 'PENDING'")
    int markEscrowIfPending(@Param("id") UUID id, @Param("now") Instant now);

    /** PENDING → CANCELLED (deadline mobile money dépassée, ou remboursement d'un paiement jamais encaissé). */
    @Modifying
    @Query("UPDATE PaymentEntity p SET p.status = 'CANCELLED' WHERE p.id = :id AND p.status = 'PENDING'")
    int markCancelledIfPending(@Param("id") UUID id);

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

    /**
     * Une ligne par paiement libéré du voyageur, dans la devise du paiement.
     * Même attribution que {@link #sumCapturedRevenueForTravelerByCurrency}
     * (annonce du bid OU fil dont il est le voyageur, LEFT JOIN des deux) : la
     * somme des lignes par devise se réconcilie exactement avec ce total.
     * Trajet = annonce du bid, sinon annonce liée au fil ; sans trajet, les
     * villes et le poids viennent de la demande de colis du fil.
     */
    @Query("""
        SELECT new com.yadony.api.matching.dto.PaymentLineRow(
            COALESCE(a.id, ta.id),
            COALESCE(a.departureCity, ta.departureCity, pr.departureCity),
            COALESCE(a.arrivalCity, ta.arrivalCity, pr.arrivalCity),
            COALESCE(a.departureDate, ta.departureDate),
            p.createdAt,
            COALESCE(b.weightKg, pr.weightKg),
            p.rail,
            UPPER(p.currency),
            p.amount - p.commissionAmount)
        FROM PaymentEntity p
        LEFT JOIN com.yadony.api.matching.BidEntity b ON p.bidId = b.id
        LEFT JOIN com.yadony.api.matching.AnnouncementEntity a ON b.announcementId = a.id
        LEFT JOIN com.yadony.api.requests.entity.NegotiationThreadEntity t ON p.negotiationThreadId = t.id
        LEFT JOIN com.yadony.api.matching.AnnouncementEntity ta ON t.travelerAnnouncementId = ta.id
        LEFT JOIN com.yadony.api.requests.entity.PackageRequestEntity pr ON t.packageRequestId = pr.id
        WHERE (a.travelerId = :travelerId OR t.travelerId = :travelerId)
          AND p.status = :status
          AND p.createdAt BETWEEN :from AND :to
        ORDER BY p.createdAt DESC
    """)
    List<com.yadony.api.matching.dto.PaymentLineRow> findReleasedLinesForTraveler(
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

    /**
     * Voyageur beneficiaire de chaque paiement : {@code [paymentId, travelerId]}, en texte (H2 rend
     * un UUID natif en {@code byte[]}, PostgreSQL en {@code UUID}). Un paiement dont
     * le colis n'est pas (encore) materialise est absent. Meme resolution que le force-release :
     * {@code bid_id}, sinon le colis materialise depuis le fil de negociation.
     */
    @Query(value = """
            SELECT CAST(p.id AS VARCHAR(36)), CAST(a.traveler_id AS VARCHAR(36)) FROM payments p
            JOIN bids b ON (b.id = p.bid_id
                            OR (p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL
                                AND b.linked_negotiation_thread_id = p.negotiation_thread_id))
            JOIN announcements a ON a.id = b.announcement_id
            WHERE p.id IN (:ids)
            """, nativeQuery = true)
    List<Object[]> findBeneficiaries(@Param("ids") Collection<UUID> ids);

    /**
     * Commissions du rail mobile money sur une période, groupées par devise ET par statut.
     *
     * <p>La commission n'existe comme mouvement nulle part : elle est la part du deposit de
     * l'expéditeur qui n'est pas repartie au voyageur, et qui reste sur le solde pawaPay de
     * yadony. Cette requête la reconstitue depuis la seule source de vérité qu'en garde le
     * système, {@code payments.commission_amount}, figée à la création du paiement.
     *
     * <p>Groupée par devise, jamais sommée à plat (XOF et XAF ne s'additionnent pas), et par
     * statut : seul {@code RELEASED} est acquis, {@code ESCROW} reste conditionnel à la
     * livraison, {@code REFUNDED} a été rendu. {@code UPPER} sur la devise pour la même raison
     * que les agrégats de revenus voisins (colonne historiquement minuscule avant V236).
     */
    @Query("""
        SELECT new com.yadony.api.payments.dto.MobileMoneyCommissionRow(
            UPPER(p.currency), p.status, COUNT(p), COALESCE(SUM(p.amount), 0), COALESCE(SUM(p.commissionAmount), 0))
        FROM PaymentEntity p
        WHERE p.rail = com.yadony.api.payments.PaymentRail.PAWAPAY
          AND p.createdAt BETWEEN :from AND :to
        GROUP BY UPPER(p.currency), p.status
        ORDER BY UPPER(p.currency)
    """)
    List<MobileMoneyCommissionRow> sumMobileMoneyCommissionsByCurrencyAndStatus(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Ventilation mensuelle des commissions mobile money acquises ({@code RELEASED}), par devise.
     * Mois de création du paiement — voir {@link MobileMoneyCommissionMonthRow}.
     */
    @Query("""
        SELECT new com.yadony.api.payments.dto.MobileMoneyCommissionMonthRow(
            YEAR(p.createdAt), MONTH(p.createdAt), UPPER(p.currency),
            COUNT(p), COALESCE(SUM(p.amount), 0), COALESCE(SUM(p.commissionAmount), 0))
        FROM PaymentEntity p
        WHERE p.rail = com.yadony.api.payments.PaymentRail.PAWAPAY
          AND p.status = com.yadony.api.payments.PaymentStatus.RELEASED
          AND p.createdAt BETWEEN :from AND :to
        GROUP BY YEAR(p.createdAt), MONTH(p.createdAt), UPPER(p.currency)
        ORDER BY YEAR(p.createdAt) DESC, MONTH(p.createdAt) DESC, UPPER(p.currency)
    """)
    List<MobileMoneyCommissionMonthRow> sumMobileMoneyCommissionsByMonth(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Volumes de la vue d'ensemble admin, par devise et statut, sur toute l'histoire des
     * paiements. Jamais de total toutes devises confondues (voir {@link PaymentVolumeRow}).
     */
    @Query("""
        SELECT new com.yadony.api.payments.dto.PaymentVolumeRow(
            UPPER(p.currency), p.status,
            COALESCE(SUM(p.amount), 0), COALESCE(SUM(p.commissionAmount), 0), COALESCE(SUM(p.refundedAmount), 0))
        FROM PaymentEntity p
        WHERE p.status IN :statuses
        GROUP BY UPPER(p.currency), p.status
        ORDER BY UPPER(p.currency)
    """)
    List<PaymentVolumeRow> sumVolumesByCurrencyAndStatus(@Param("statuses") Collection<PaymentStatus> statuses);

    /**
     * Paiements ESCROW retenus a la livraison (V270, {@code payout_held_at}) dont le voyageur est
     * {@code travelerId}. Le voyageur se lit sur l'annonce du colis, classique ou materialise
     * depuis un fil de negociation (meme resolution que le force-release).
     */
    @Query(value = """
            SELECT COUNT(*) FROM payments p
            JOIN bids b ON (b.id = p.bid_id
                            OR (p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL
                                AND b.linked_negotiation_thread_id = p.negotiation_thread_id))
            JOIN announcements a ON a.id = b.announcement_id
            WHERE p.deleted_at IS NULL
              AND p.status = 'ESCROW'
              AND p.payout_held_at IS NOT NULL
              AND a.traveler_id = :travelerId
            """, nativeQuery = true)
    long countHeldEscrowForTraveler(@Param("travelerId") UUID travelerId);

    /**
     * Marque le versement comme retenu (beneficiaire gele) sans toucher au statut : le paiement
     * reste ESCROW. La premiere date est conservee, une livraison rejouee ne la deplace pas.
     */
    @Modifying
    @Query(value = """
            UPDATE payments SET payout_held_at = :heldAt
            WHERE id = :id AND status = 'ESCROW' AND payout_held_at IS NULL
            """, nativeQuery = true)
    int markPayoutHeld(@Param("id") UUID id, @Param("heldAt") LocalDateTime heldAt);

    /**
     * Trace le Transfer Stripe du versement (V310). Écriture ciblée, jamais par le flush d'une
     * entité chargée avant le claim ESCROW → RELEASED. N'écrase pas un identifiant déjà posé.
     */
    @Modifying
    @Query(value = """
            UPDATE payments SET stripe_transfer_id = :transferId
            WHERE id = :id AND stripe_transfer_id IS NULL
            """, nativeQuery = true)
    int recordStripeTransferId(@Param("id") UUID id, @Param("transferId") String transferId);

    /**
     * Paiements carte à relire chez Stripe par le rapprochement quotidien : tous ceux encore
     * ouverts ({@code open}, quel que soit leur âge) et tous ceux créés depuis {@code since}.
     */
    @Query("SELECT p FROM PaymentEntity p WHERE p.rail = com.yadony.api.payments.PaymentRail.STRIPE "
            + "AND p.stripePaymentIntentId IS NOT NULL AND (p.status IN :open OR p.createdAt >= :since)")
    List<PaymentEntity> findForStripeReconciliation(@Param("open") java.util.Collection<PaymentStatus> open,
                                                    @Param("since") java.time.LocalDateTime since);
}
