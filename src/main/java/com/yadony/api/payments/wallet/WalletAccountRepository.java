package com.yadony.api.payments.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletAccountRepository extends JpaRepository<WalletAccountEntity, UUID> {

    /**
     * Crée le wallet (solde nul) s'il n'existe pas, de façon atomique : deux créations
     * simultanées ne violent plus {@code wallet_accounts_user_id_currency_unique} (V201),
     * qu'une transaction englobe l'appel ou non. Le perdant de la course attend le commit
     * du gagnant (verrou de la ligne en conflit) puis ne fait rien.
     *
     * @return 1 si la ligne a été insérée, 0 si elle existait déjà
     */
    @Modifying
    @Query(value = """
            INSERT INTO wallet_accounts
                (id, user_id, currency, balance, refund_eligible_amount, created_at, updated_at)
            VALUES (:id, :userId, :currency, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("userId") UUID userId,
                       @Param("currency") String currency);

    Optional<WalletAccountEntity> findByUserIdAndCurrency(UUID userId, String currency);

    List<WalletAccountEntity> findAllByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletAccountEntity w WHERE w.userId = :userId AND w.currency = :currency")
    Optional<WalletAccountEntity> findByUserIdAndCurrencyForUpdate(
            @Param("userId") UUID userId, @Param("currency") String currency);

}
