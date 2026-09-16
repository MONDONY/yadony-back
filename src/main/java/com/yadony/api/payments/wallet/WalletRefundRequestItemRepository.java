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

    /**
     * Un PaymentIntent peut porter plusieurs items dans le temps (item FAILED puis item du
     * ticket enfant, remboursements partiels successifs), mais un seul item actif à la fois
     * ({@code uq_wallet_refund_request_items_pi_active}, V259) : un seul PROCESSING.
     */
    Optional<WalletRefundRequestItemEntity> findByPaymentIntentIdAndStatus(
            String paymentIntentId, WalletRefundItemStatus status);

    List<WalletRefundRequestItemEntity> findByWalletTransactionIdIn(Collection<UUID> walletTransactionIds);

    /** Items d'une demande encore à émettre vers Stripe, verrouillés contre une émission concurrente. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM WalletRefundRequestItemEntity i WHERE i.refundRequestId = :requestId "
            + "AND i.status = :status AND i.stripeRefundId IS NULL ORDER BY i.createdAt ASC")
    List<WalletRefundRequestItemEntity> findUnissuedForUpdate(@Param("requestId") UUID requestId,
                                                              @Param("status") WalletRefundItemStatus status);

    @Query("SELECT DISTINCT i.refundRequestId FROM WalletRefundRequestItemEntity i, WalletRefundRequestEntity r "
            + "WHERE r.id = i.refundRequestId AND r.channel = :channel AND r.status = :requestStatus "
            + "AND i.status = :itemStatus AND i.stripeRefundId IS NULL AND i.createdAt < :cutoff")
    List<UUID> findRequestIdsWithUnissuedItems(@Param("channel") WalletRefundChannel channel,
                                               @Param("requestStatus") WalletRefundRequestStatus requestStatus,
                                               @Param("itemStatus") WalletRefundItemStatus itemStatus,
                                               @Param("cutoff") Instant cutoff);
}
