package com.yadony.api.payments.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletAccountRepository extends JpaRepository<WalletAccountEntity, UUID> {

    Optional<WalletAccountEntity> findByUserIdAndCurrency(UUID userId, String currency);

    List<WalletAccountEntity> findAllByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletAccountEntity w WHERE w.userId = :userId AND w.currency = :currency")
    Optional<WalletAccountEntity> findByUserIdAndCurrencyForUpdate(
            @Param("userId") UUID userId, @Param("currency") String currency);

}
