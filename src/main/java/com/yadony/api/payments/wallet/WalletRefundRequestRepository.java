package com.yadony.api.payments.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRefundRequestRepository extends JpaRepository<WalletRefundRequestEntity, UUID> {

    Optional<WalletRefundRequestEntity> findByUserIdAndCurrencyAndStatus(
            UUID userId, String currency, WalletRefundRequestStatus status);

    Optional<WalletRefundRequestEntity> findByUserIdAndCurrencyAndStatusIn(
            UUID userId, String currency, List<WalletRefundRequestStatus> statuses);

    boolean existsByUserIdAndCurrencyAndStatusIn(
            UUID userId, String currency, List<WalletRefundRequestStatus> statuses);

    List<WalletRefundRequestEntity> findAllByUserIdAndStatus(UUID userId, WalletRefundRequestStatus status);

    List<WalletRefundRequestEntity> findAllByUserIdOrderByRequestedAtDesc(UUID userId);

    Page<WalletRefundRequestEntity> findAllByStatusOrderByRequestedAtAsc(
            WalletRefundRequestStatus status, Pageable pageable);
}
