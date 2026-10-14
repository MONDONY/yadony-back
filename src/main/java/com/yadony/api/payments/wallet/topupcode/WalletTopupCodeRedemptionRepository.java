package com.yadony.api.payments.wallet.topupcode;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WalletTopupCodeRedemptionRepository extends JpaRepository<WalletTopupCodeRedemptionEntity, UUID> {

    boolean existsByCodeIdAndUserId(UUID codeId, UUID userId);

    List<WalletTopupCodeRedemptionEntity> findByCodeIdOrderByCreatedAtDesc(UUID codeId);
}
