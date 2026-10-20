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
     * Prend le bail d'un versement en attente dont le bail est échu (reprise par le job), compte la
     * tentative et pose un NOUVEAU jeton de bail : toute écriture d'un détenteur précédent (bail
     * expiré pendant un appel trop long) est désormais refusée. 1 si ce traitement le détient.
     */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.leaseUntil = :until, i.attempts = i.attempts + 1,
                   i.lastError = NULL, i.leaseToken = :token
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
               AND i.leaseUntil <= :now
            """)
    int claimLease(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until,
                   @Param("token") UUID token);

    /**
     * Juste avant {@code Transfer.create} : trace l'envoi, sous le jeton du détenteur. 0 si le bail
     * a été repris entre-temps : l'appel n'est alors PAS fait (aucun envoi hors bail).
     */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.createSentAt = :now
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING AND i.leaseToken = :token
            """)
    int markCreateSent(@Param("id") UUID id, @Param("token") UUID token, @Param("now") LocalDateTime now);

    /**
     * Finalisation : 1 si ce traitement a fait passer le versement en DONE, 0 s'il ne l'était plus.
     * Sans jeton, volontairement : DONE est l'état sûr (versement prouvé chez Stripe, plus aucun
     * remboursement possible), tout traitement qui tient la preuve peut le poser.
     */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.DONE,
                   i.stripeObjectId = :objectId, i.lastError = NULL
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
            """)
    int markDone(@Param("id") UUID id, @Param("objectId") String objectId);

    /** Aucun versement chez Stripe : 1 si CE détenteur du bail annule la réservation. */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.REVERTED,
                   i.lastError = :error
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING AND i.leaseToken = :token
            """)
    int markReverted(@Param("id") UUID id, @Param("token") UUID token, @Param("error") String error);

    /** Issue incertaine : reste PENDING, prochaine reprise à {@code retryAt} (détenteur du bail seulement). */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.lastError = :error, i.leaseUntil = :retryAt
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING AND i.leaseToken = :token
            """)
    int recordRetry(@Param("id") UUID id, @Param("token") UUID token, @Param("error") String error,
                    @Param("retryAt") LocalDateTime retryAt);

    /**
     * Bail pris par un super-admin pour annuler la réservation : seulement si aucun appel n'est en
     * vol, c'est-à-dire bail échu, ou réservation en attente de reprise après un échec ({@code
     * last_error} posé ; un traitement en cours l'efface en prenant son bail). Nouveau jeton. 1 si pris.
     */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.leaseUntil = :until, i.lastError = NULL, i.leaseToken = :token
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
               AND (i.leaseUntil <= :now OR i.lastError IS NOT NULL)
            """)
    int claimForAdmin(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until,
                      @Param("token") UUID token);

    /** Annulation admin après recherche Stripe vide : 1 si ce détenteur du bail l'annule. */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.CANCELLED,
                   i.lastError = :reason
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING AND i.leaseToken = :token
            """)
    int markCancelled(@Param("id") UUID id, @Param("token") UUID token, @Param("reason") String reason);

    /** Annulation admin impossible : bail rendu, rien d'autre ne change. */
    @Modifying
    @Query("""
            UPDATE StripeReleaseIntentEntity i SET i.leaseUntil = :now, i.lastError = :error
             WHERE i.id = :id AND i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING AND i.leaseToken = :token
            """)
    int releaseAdminLease(@Param("id") UUID id, @Param("token") UUID token, @Param("now") LocalDateTime now,
                          @Param("error") String error);

    /** Versements en attente dont le bail est échu, les plus anciens d'abord. */
    @Query("""
            SELECT i.id FROM StripeReleaseIntentEntity i
             WHERE i.status = com.yadony.api.payments.StripeReleaseIntentEntity.Status.PENDING
               AND i.leaseUntil <= :now
             ORDER BY i.leaseUntil ASC
            """)
    List<UUID> findDueIds(@Param("now") LocalDateTime now, Pageable page);

    /**
     * Vrai si un versement carte (transfert, tout statut) a déjà visé ce compte Connect. Sert au
     * verrou du pays de résidence ({@code CountryLockService}).
     */
    boolean existsByDestination(String destination);

    boolean existsByDestinationAndStatus(String destination, StripeReleaseIntentEntity.Status status);
}
