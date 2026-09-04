package com.yadony.api.payments.pawapay;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PawapayOperationRepository extends JpaRepository<PawapayOperationEntity, UUID> {

    Optional<PawapayOperationEntity> findFirstByPaymentIdAndKindOrderByCreatedAtDesc(
            UUID paymentId, PawapayOperationKind kind);

    Optional<PawapayOperationEntity> findFirstByPaymentIdAndKindAndStatusInOrderByCreatedAtDesc(
            UUID paymentId, PawapayOperationKind kind, Collection<PawapayOperationStatus> statuses);

    boolean existsByPaymentIdAndKindAndStatusIn(
            UUID paymentId, PawapayOperationKind kind, Collection<PawapayOperationStatus> statuses);

    List<PawapayOperationEntity> findByStatusInAndUpdatedAtBefore(
            Collection<PawapayOperationStatus> statuses, LocalDateTime before);

    List<PawapayOperationEntity> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    Page<PawapayOperationEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Point de transition unique (callback ET poller) : un seul gagnant, jamais de départ
     * depuis un état final. Les valeurs nulles ne rasent rien (COALESCE) : un callback
     * COMPLETED sans authorizationUrl garde celle posée par le PROCESSING Wave.
     * @return 1 si la ligne a bougé, 0 si elle était déjà finale
     */
    // clearAutomatically=true : ce bulk UPDATE contourne le persistence context, un
    // findById() dans la même transaction (callback + relecture immédiate) renverrait
    // sinon l'entité pré-transition mise en cache par le saveAndFlush initial (même
    // mécanisme que UserRepository#reactivateByFirebaseUid). flushAutomatically=true
    // en binôme obligatoire (voir memory backend_modifying_clear_flush_trap) : sans lui,
    // clearAutomatically jetterait aussi toute écriture encore non flushée de la
    // transaction en cours.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE PawapayOperationEntity o
           SET o.status = :status,
               o.failureCode = COALESCE(:failureCode, o.failureCode),
               o.failureMessage = COALESCE(:failureMessage, o.failureMessage),
               o.providerTransactionId = COALESCE(:providerTransactionId, o.providerTransactionId),
               o.authorizationUrl = COALESCE(:authorizationUrl, o.authorizationUrl),
               o.rawCallback = COALESCE(:raw, o.rawCallback),
               o.callbackReceivedAt = COALESCE(:callbackAt, o.callbackReceivedAt),
               o.lastPolledAt = COALESCE(:polledAt, o.lastPolledAt),
               o.finalizedAt = COALESCE(:finalizedAt, o.finalizedAt),
               o.updatedAt = :now
         WHERE o.id = :id
           AND o.status NOT IN (com.yadony.api.payments.pawapay.PawapayOperationStatus.COMPLETED,
                                com.yadony.api.payments.pawapay.PawapayOperationStatus.FAILED,
                                com.yadony.api.payments.pawapay.PawapayOperationStatus.SUBMIT_REJECTED)
        """)
    int applyTransition(@Param("id") UUID id,
                        @Param("status") PawapayOperationStatus status,
                        @Param("failureCode") String failureCode,
                        @Param("failureMessage") String failureMessage,
                        @Param("providerTransactionId") String providerTransactionId,
                        @Param("authorizationUrl") String authorizationUrl,
                        @Param("raw") String raw,
                        @Param("callbackAt") LocalDateTime callbackAt,
                        @Param("polledAt") LocalDateTime polledAt,
                        @Param("finalizedAt") LocalDateTime finalizedAt,
                        @Param("now") LocalDateTime now);
}
