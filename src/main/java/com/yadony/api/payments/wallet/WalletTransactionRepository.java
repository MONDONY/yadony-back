package com.yadony.api.payments.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, UUID> {

    Page<WalletTransactionEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<WalletTransactionEntity> findByIdempotencyKey(String idempotencyKey);

    // Journal admin des mouvements (cf. WalletAdminAdjustmentService#transactions) : le tri
    // vient du Pageable, un filtre absent choisit la variante sans ce critère.
    Page<WalletTransactionEntity> findByUserId(UUID userId, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndCurrency(UUID userId, String currency, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndType(UUID userId, WalletTransactionType type, Pageable pageable);

    Page<WalletTransactionEntity> findByUserIdAndCurrencyAndType(UUID userId, String currency,
                                                                 WalletTransactionType type, Pageable pageable);

    boolean existsByUserIdAndBidIdAndType(UUID userId, UUID bidId, WalletTransactionType type);

    boolean existsByUserId(UUID userId);


    List<WalletTransactionEntity> findAllByUserIdAndBidIdAndType(UUID userId, UUID bidId, WalletTransactionType type);

    List<WalletTransactionEntity> findByUserIdAndCurrencyOrderByCreatedAtAsc(UUID userId, String currency);
}
