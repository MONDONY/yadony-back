package com.yadony.api.payments.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface WalletExternalRefundHoldRepository extends JpaRepository<WalletExternalRefundHoldEntity, UUID> {

    boolean existsByUserIdAndCurrencyAndStatus(UUID userId, String currency, WalletExternalRefundHoldStatus status);

    boolean existsByStripeRefundId(String stripeRefundId);

    List<WalletExternalRefundHoldEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Levée atomique d'un gel encore OPEN de cet utilisateur : deux admins qui lèvent en même temps
     * n'écrivent qu'une décision (UPDATE conditionnel, exécutable sous H2).
     *
     * @return 1 si le gel était ouvert et vient d'être levé, 0 sinon
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WalletExternalRefundHoldEntity h SET h.status = "
            + "com.yadony.api.payments.wallet.WalletExternalRefundHoldStatus.RELEASED, "
            + "h.releasedBy = :adminId, h.releasedAt = :releasedAt, h.releaseNote = :note "
            + "WHERE h.id = :id AND h.userId = :userId "
            + "AND h.status = com.yadony.api.payments.wallet.WalletExternalRefundHoldStatus.OPEN")
    int release(@Param("id") UUID id, @Param("userId") UUID userId, @Param("adminId") UUID adminId,
                @Param("releasedAt") LocalDateTime releasedAt, @Param("note") String note);
}
