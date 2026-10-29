package com.yadony.api.payments.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface WalletAdjustmentRequestRepository extends JpaRepository<WalletAdjustmentRequestEntity, UUID> {

    Optional<WalletAdjustmentRequestEntity> findByIdempotencyKey(String idempotencyKey);

    Page<WalletAdjustmentRequestEntity> findByStatus(WalletAdjustmentRequestStatus status, Pageable pageable);

    /**
     * Prise en charge atomique d'une demande encore PENDING : un seul des deux admins qui
     * approuvent ou rejettent en même temps l'obtient (UPDATE conditionnel, exécutable sous H2).
     *
     * @return 1 si la demande était PENDING et lui revient, 0 sinon
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WalletAdjustmentRequestEntity r SET r.status = :decision, r.decidedBy = :adminId, "
            + "r.decidedAt = :decidedAt, r.decisionNote = :note "
            + "WHERE r.id = :id AND r.status = com.yadony.api.payments.wallet.WalletAdjustmentRequestStatus.PENDING")
    int claim(@Param("id") UUID id, @Param("decision") WalletAdjustmentRequestStatus decision,
              @Param("adminId") UUID adminId, @Param("decidedAt") LocalDateTime decidedAt,
              @Param("note") String note);
}
