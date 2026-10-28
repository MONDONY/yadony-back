package com.yadony.api.payments.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletDisputeHoldRepository extends JpaRepository<WalletDisputeHoldEntity, UUID> {

    boolean existsByUserIdAndCurrencyAndStatus(UUID userId, String currency, WalletDisputeHoldStatus status);

    Optional<WalletDisputeHoldEntity> findByStripeDisputeId(String stripeDisputeId);

    /** Clôture d'un litige : verrou de la ligne, pour qu'un webhook rejoué ne recrédite jamais deux fois. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM WalletDisputeHoldEntity h WHERE h.stripeDisputeId = :disputeId")
    Optional<WalletDisputeHoldEntity> findByStripeDisputeIdForUpdate(@Param("disputeId") String disputeId);
}
