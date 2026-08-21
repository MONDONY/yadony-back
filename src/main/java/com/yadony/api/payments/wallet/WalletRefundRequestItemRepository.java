package com.yadony.api.payments.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRefundRequestItemRepository extends JpaRepository<WalletRefundRequestItemEntity, UUID> {

    List<WalletRefundRequestItemEntity> findByRefundRequestId(UUID refundRequestId);

    Optional<WalletRefundRequestItemEntity> findByPaymentIntentId(String paymentIntentId);

    List<WalletRefundRequestItemEntity> findByWalletTransactionIdIn(Collection<UUID> walletTransactionIds);
}
