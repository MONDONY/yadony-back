package com.yadony.api.payments.bankpayout;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankPayoutTrackingRepository extends JpaRepository<BankPayoutTrackingEntity, UUID> {

    Optional<BankPayoutTrackingEntity> findByPaymentId(UUID paymentId);

    List<BankPayoutTrackingEntity> findByConnectedAccountIdAndDestinationPaymentIdIn(
            String connectedAccountId, Collection<String> destinationPaymentIds);

    List<BankPayoutTrackingEntity> findByConnectedAccountIdAndPayoutIdIn(
            String connectedAccountId, Collection<String> payoutIds);

    List<BankPayoutTrackingEntity> findByConnectedAccountIdAndDestinationPaymentIdIsNull(String connectedAccountId);

    /** Lignes à compléter : destination_payment ou date de disponibilité encore inconnue. */
    @Query("SELECT t FROM BankPayoutTrackingEntity t WHERE (t.destinationPaymentId IS NULL OR t.availableOn IS NULL) "
            + "AND t.createdAt >= :since ORDER BY t.createdAt")
    List<BankPayoutTrackingEntity> findIncomplete(@Param("since") LocalDateTime since, Pageable page);

    /** Comptes connectés dont un virement suivi n'est pas encore arrivé sur la banque. */
    @Query("SELECT DISTINCT t.connectedAccountId FROM BankPayoutTrackingEntity t "
            + "WHERE t.createdAt >= :since AND (t.payoutStatus IS NULL OR t.payoutStatus <> 'paid')")
    List<String> findAccountsAwaitingBank(@Param("since") LocalDateTime since);

    @Query("SELECT MIN(t.createdAt) FROM BankPayoutTrackingEntity t WHERE t.connectedAccountId = :account "
            + "AND t.createdAt >= :since AND (t.payoutStatus IS NULL OR t.payoutStatus <> 'paid')")
    Optional<LocalDateTime> findOldestAwaitingBank(@Param("account") String account,
                                                   @Param("since") LocalDateTime since);
}
