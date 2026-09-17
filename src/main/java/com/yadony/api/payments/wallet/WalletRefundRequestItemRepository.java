package com.yadony.api.payments.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRefundRequestItemRepository extends JpaRepository<WalletRefundRequestItemEntity, UUID> {

    List<WalletRefundRequestItemEntity> findByRefundRequestId(UUID refundRequestId);

    /** Items de plusieurs demandes en une requête (contrat API : frais, net, destination). */
    List<WalletRefundRequestItemEntity> findByRefundRequestIdIn(Collection<UUID> refundRequestIds);

    /**
     * Un PaymentIntent peut porter plusieurs items dans le temps (item FAILED puis item du
     * ticket enfant, remboursements partiels successifs), mais un seul item actif à la fois
     * ({@code uq_wallet_refund_request_items_pi_active}, V259) : un seul PROCESSING.
     */
    Optional<WalletRefundRequestItemEntity> findByPaymentIntentIdAndStatus(
            String paymentIntentId, WalletRefundItemStatus status);

    List<WalletRefundRequestItemEntity> findByWalletTransactionIdIn(Collection<UUID> walletTransactionIds);

    /** Items d'une demande encore à émettre (Stripe ou pawaPay), verrouillés contre une émission concurrente. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM WalletRefundRequestItemEntity i WHERE i.refundRequestId = :requestId "
            + "AND i.status = :status AND i.stripeRefundId IS NULL AND i.pawapayRefundId IS NULL "
            + "AND i.pawapayPayoutId IS NULL ORDER BY i.createdAt ASC")
    List<WalletRefundRequestItemEntity> findUnissuedForUpdate(@Param("requestId") UUID requestId,
                                                              @Param("status") WalletRefundItemStatus status);

    /**
     * Item d'un remboursement pawaPay par l'opération REFUND qui le porte, verrouillé : l'issue
     * pawaPay (écouteur) et le rejet synchrone de l'initiation ne le font jamais avancer en même
     * temps (deux replis par versement pour un seul item).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WalletRefundRequestItemEntity> findByPawapayRefundId(UUID pawapayRefundId);

    /**
     * Demande de l'item lié à une opération pawaPay (REFUND ou PAYOUT), lue SANS verrou ni
     * chargement d'entité : sert à verrouiller la demande avant l'item (ordre demande puis item,
     * comme {@code issuePendingItems}), l'item étant ensuite relu verrouillé.
     */
    @Query("SELECT i.refundRequestId FROM WalletRefundRequestItemEntity i "
            + "WHERE i.pawapayRefundId = :operationId OR i.pawapayPayoutId = :operationId")
    Optional<UUID> findRefundRequestIdByPawapayOperationId(@Param("operationId") UUID operationId);

    /** Item d'un remboursement pawaPay par l'opération PAYOUT qui le porte, verrouillé (voir ci-dessus). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WalletRefundRequestItemEntity> findByPawapayPayoutId(UUID pawapayPayoutId);

    /**
     * Demandes dont un item attend encore son émission : aucun identifiant Stripe ni pawaPay
     * posé. Un item pawaPay lié à une opération est PROCESSING, jamais repris ici : c'est le
     * poller pawaPay qui tranche une initiation restée sans réponse.
     */
    @Query("SELECT DISTINCT i.refundRequestId FROM WalletRefundRequestItemEntity i, WalletRefundRequestEntity r "
            + "WHERE r.id = i.refundRequestId AND r.channel IN :channels AND r.status = :requestStatus "
            + "AND i.status = :itemStatus AND i.stripeRefundId IS NULL AND i.pawapayRefundId IS NULL "
            + "AND i.pawapayPayoutId IS NULL AND i.createdAt < :cutoff")
    List<UUID> findRequestIdsWithUnissuedItems(@Param("channels") Collection<WalletRefundChannel> channels,
                                               @Param("requestStatus") WalletRefundRequestStatus requestStatus,
                                               @Param("itemStatus") WalletRefundItemStatus itemStatus,
                                               @Param("cutoff") Instant cutoff);
}
