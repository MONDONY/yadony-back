package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NegotiationThreadRepository extends JpaRepository<NegotiationThreadEntity, UUID> {

    /**
     * True iff at least one thread exists for (request, traveler) — regardless of status.
     * Used to assert the user is a participant. Derived count works even when multiple
     * historical threads exist (one active + N terminal), unlike a unique-result Optional.
     */
    boolean existsByPackageRequestIdAndTravelerId(UUID packageRequestId, UUID travelerId);

    /**
     * Active (non-terminal) thread for the (request, traveler) pair.
     * REJECTED / AUTO_REJECTED / EXPIRED threads are intentionally excluded so the
     * traveler can retry with a fresh proposal — see V63 unique-index change.
     */
    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.packageRequestId = :requestId
          AND t.travelerId = :travelerId
          AND t.status IN (
              com.yadony.api.requests.entity.NegotiationThreadStatus.OPEN,
              com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_TRIP,
              com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_PAYMENT,
              com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_COMMISSION,
              com.yadony.api.requests.entity.NegotiationThreadStatus.ACCEPTED
          )
    """)
    Optional<NegotiationThreadEntity> findActiveByPackageRequestIdAndTravelerId(
        @Param("requestId") UUID requestId,
        @Param("travelerId") UUID travelerId
    );

    List<NegotiationThreadEntity> findByPackageRequestId(UUID packageRequestId);

    /**
     * The single ACCEPTED thread for a given dedicated trip. Since Task 4, a trip can be
     * linked to multiple concurrent OPEN offers (spec §3.8), so the 1:1 relation only holds
     * for the ACCEPTED status — filtering by it is required, otherwise multiple linked
     * threads would make this throw IncorrectResultSizeDataAccessException. Used by
     * {@code openSurplus} to re-check the negotiation reached ACCEPTED before opening surplus.
     */
    Optional<NegotiationThreadEntity> findByTravelerAnnouncementIdAndStatus(
        UUID travelerAnnouncementId, NegotiationThreadStatus status);

    /**
     * True iff at least one non-terminal (active) thread is linked to this trip announcement.
     * Used to block unpublishing a trip that is still in play on a negotiation — restricted to
     * the active set so a REJECTED/CANCELLED/AUTO_REJECTED/EXPIRED thread (dead, no longer
     * blocking anything) doesn't permanently prevent the traveler from unpublishing.
     */
    @Query("""
        SELECT COUNT(t) > 0 FROM NegotiationThreadEntity t
        WHERE t.travelerAnnouncementId = :announcementId
          AND t.status IN (
              com.yadony.api.requests.entity.NegotiationThreadStatus.OPEN,
              com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_TRIP,
              com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_PAYMENT,
              com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_COMMISSION
          )
    """)
    boolean existsActiveByTravelerAnnouncementId(@Param("announcementId") UUID announcementId);

    long countByTravelerIdAndStatus(UUID travelerId, NegotiationThreadStatus status);

    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.status = 'OPEN'
          AND t.lastActivityAt < :cutoff
    """)
    List<NegotiationThreadEntity> findInactive(@Param("cutoff") LocalDateTime cutoff);

    @Query("""
        SELECT count(t) FROM NegotiationThreadEntity t
        WHERE t.travelerId = :travelerId AND t.createdAt > :since
    """)
    long countCreatedBy(@Param("travelerId") UUID travelerId, @Param("since") LocalDateTime since);

    @Query("SELECT t FROM NegotiationThreadEntity t WHERE t.status = 'AWAITING_TRIP' AND t.lastActivityAt < :cutoff")
    List<NegotiationThreadEntity> findAwaitingTripExpired(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT t FROM NegotiationThreadEntity t WHERE t.status = 'AWAITING_PAYMENT' AND t.lastActivityAt < :cutoff")
    List<NegotiationThreadEntity> findAwaitingPaymentExpired(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Threads {@code AWAITING_COMMISSION} dont la fenêtre de règlement (délai
     * configurable, cf. {@code NegotiationProperties.commissionWindowMinutes})
     * est dépassée sans que le voyageur n'ait scellé l'accord. Consommée par
     * {@code CommissionWindowExpiryRunner}.
     */
    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.status = com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_COMMISSION
          AND t.lastActivityAt < :cutoff
    """)
    List<NegotiationThreadEntity> findExpiredAwaitingCommission(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Threads {@code AUTO_REJECTED} ou {@code EXPIRED} portant un PaymentIntent de
     * commission (cash) qui n'a encore jamais été remboursé. Ce sont des fils
     * perdants : soit un concurrent a scellé l'accord pendant que ce voyageur
     * réglait sa commission (AUTO_REJECTED), soit le délai a expiré alors qu'un
     * débit (carte, éventuellement en attente de 3DS) était en cours (EXPIRED).
     * Dans les deux cas, si Stripe a fini par encaisser, personne n'appelle
     * spontanément le remboursement — {@code CommissionWindowExpiryRunner} balaie
     * ce trou via {@code CashGatePort#refundNegotiationCommissionIfCharged}, seul
     * chemin autorisé depuis {@code requests/} vers {@code payments/cash}.
     */
    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.status IN (
            com.yadony.api.requests.entity.NegotiationThreadStatus.AUTO_REJECTED,
            com.yadony.api.requests.entity.NegotiationThreadStatus.EXPIRED
        )
          AND t.commissionPaymentIntentId IS NOT NULL
          AND (t.commissionStatus IS NULL OR t.commissionStatus <> 'REFUNDED')
    """)
    List<NegotiationThreadEntity> findUnrefundedChargedCommissions();

    /**
     * All threads where the user is participant — either traveler directly,
     * or sender via the linked package_request.
     * Used by GET /negotiations/me to power the inbox view.
     */
    @Query("""
        SELECT t FROM NegotiationThreadEntity t
        WHERE t.travelerId = :userId
           OR t.packageRequestId IN (
                SELECT p.id FROM PackageRequestEntity p WHERE p.senderId = :userId
           )
        ORDER BY t.lastActivityAt DESC
    """)
    List<NegotiationThreadEntity> findByParticipant(@Param("userId") UUID userId);
}
