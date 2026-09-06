package com.yadony.api.payments.pawapay;

import com.yadony.api.payments.pawapay.dto.PawapayOpenOperation;
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

    /**
     * Opérations encore ouvertes à réconcilier, bornées par {@code pageable} : sans borne, un
     * incident prolongé chez pawaPay pourrait accumuler des centaines d'opérations {@code OPEN}
     * et faire durer un seul passage du poller des heures durant, sur l'unique pool de
     * scheduling partagé par tous les crons du dépôt. Le poller l'appelle triée par
     * {@code updatedAt} croissant : les plus anciennes d'abord, la fenêtre finit par se vider
     * passage après passage même si le flux entrant ne tarit jamais. Projection : le poller ne
     * lit que quatre colonnes scalaires, inutile d'hydrater (et de déchiffrer) {@code msisdn}
     * et {@code raw_callback} pour chaque ligne du lot.
     */
    @Query("""
        SELECT new com.yadony.api.payments.pawapay.dto.PawapayOpenOperation(o.id, o.kind, o.status, o.createdAt)
          FROM PawapayOperationEntity o
         WHERE o.status IN :statuses AND o.updatedAt < :before
        """)
    List<PawapayOpenOperation> findOpenForReconciliation(@Param("statuses") Collection<PawapayOperationStatus> statuses,
                                                          @Param("before") LocalDateTime before, Pageable pageable);

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

    /**
     * Marque la soumission (ACCEPTED / SUBMIT_REJECTED, ou statut inchangé
     * pour un DUPLICATE_IGNORED qui repasse {@code status = CREATED}) — mais
     * seulement si l'opération est encore CREATED au moment de l'écriture.
     * <p>
     * Sur cette paire précise (ce UPDATE vs {@link #applyTransition}), le
     * verrou optimiste {@code @Version} de l'entité ne protège rien :
     * {@code applyTransition} est un bulk UPDATE JPQL qui ne l'incrémente
     * jamais, donc un {@code save()} d'entité classique passerait toujours son
     * contrôle de version même après qu'un callback ait fait avancer la ligne.
     * Sans cette clause {@code WHERE ... = CREATED}, un callback pawaPay plus
     * rapide que la réponse HTTP de notre propre appel d'initiation serait
     * silencieusement écrasé par ce {@code markSubmitted} tardif — y compris
     * dans le sens inverse : un SUBMIT_REJECTED (final) poserait
     * {@code finalizedAt} sur une opération en réalité déjà COMPLETED chez
     * pawaPay, et plus aucun poller ne pourrait la corriger.
     * @return 1 si la ligne a été marquée, 0 si elle n'était déjà plus CREATED
     *         (callback ou poller déjà passés devant)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE PawapayOperationEntity o
           SET o.status = :status,
               o.submittedAt = :now,
               o.failureCode = COALESCE(:failureCode, o.failureCode),
               o.failureMessage = COALESCE(:failureMessage, o.failureMessage),
               o.finalizedAt = COALESCE(:finalizedAt, o.finalizedAt),
               o.updatedAt = :now
         WHERE o.id = :id
           AND o.status = com.yadony.api.payments.pawapay.PawapayOperationStatus.CREATED
        """)
    int markSubmittedIfStillCreated(@Param("id") UUID id,
                                    @Param("status") PawapayOperationStatus status,
                                    @Param("failureCode") String failureCode,
                                    @Param("failureMessage") String failureMessage,
                                    @Param("finalizedAt") LocalDateTime finalizedAt,
                                    @Param("now") LocalDateTime now);
}
