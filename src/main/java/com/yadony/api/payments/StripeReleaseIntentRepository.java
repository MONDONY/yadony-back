package com.yadony.api.payments;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Versements carte réservés ({@link StripeReleaseIntentEntity}). Chaque transition est un UPDATE
 * conditionnel sur {@code status = PENDING} : un seul traitement finalise ou annule un versement,
 * quel que soit le nombre de reprises concurrentes.
 */
public interface StripeReleaseIntentRepository extends JpaRepository<StripeReleaseIntentEntity, UUID> {

    Optional<StripeReleaseIntentEntity> findByPaymentId(UUID paymentId);

    /**
     * Prend le bail d'un versement en attente dont le bail est échu (reprise par le job) et compte
     * la tentative. 1 si ce traitement le détient jusqu'à {@code until}, 0 sinon (finalisé, annulé
     * ou détenu par un autre traitement).
     */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.leaseUntil = :until, i.attempts = i.attempts + 1
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
               AND i.leaseUntil <= :now
            """)
    int claimLease(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

    /** Finalisation : 1 si ce traitement a fait passer le versement en DONE, 0 s'il ne l'était plus. */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.DONE,
                   i.stripeObjectId = :objectId, i.lastError = NULL
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
            """)
    int markDone(@Param("id") UUID id, @Param("objectId") String objectId);

    /** Stripe a refusé (aucun versement créé) : 1 si ce traitement annule la réservation. */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.REVERTED,
                   i.lastError = :error
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
            """)
    int markReverted(@Param("id") UUID id, @Param("error") String error);

    /** Issue incertaine (réseau, 5xx) : reste PENDING, prochaine reprise à {@code retryAt}. */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.lastError = :error, i.leaseUntil = :retryAt
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
            """)
    int recordRetry(@Param("id") UUID id, @Param("error") String error, @Param("retryAt") LocalDateTime retryAt);

    /** Versements en attente dont le bail est échu, les plus anciens d'abord. */
    @Query("""
            SELECT i.id FROM StripeReleaseIntentEntity i
             WHERE i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
               AND i.leaseUntil <= :now
             ORDER BY i.leaseUntil ASC
            """)
    List<UUID> findDueIds(@Param("now") LocalDateTime now, Pageable page);
}
