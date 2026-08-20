package com.yadony.api.payments.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, UUID> {

    Page<WalletTransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<WalletTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    boolean existsByUserIdAndBidIdAndType(UUID userId, UUID bidId, WalletTransactionType type);

    boolean existsByUserId(UUID userId);

    Optional<WalletTransactionEntity> findByUserIdAndBidIdAndType(UUID userId, UUID bidId, WalletTransactionType type);

    List<WalletTransactionEntity> findByUserIdAndCurrencyAndTypeAndCreatedAtGreaterThanEqual(
            UUID userId, String currency, WalletTransactionType type, Instant since);
}
