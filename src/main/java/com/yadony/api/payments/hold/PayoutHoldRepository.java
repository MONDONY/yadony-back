package com.yadony.api.payments.hold;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutHoldRepository extends JpaRepository<PayoutHoldEntity, UUID> {

    @Query("SELECT h FROM PayoutHoldEntity h WHERE h.userId = :userId AND h.releasedAt IS NULL ORDER BY h.heldSince")
    List<PayoutHoldEntity> findActiveByUserId(@Param("userId") UUID userId);

    @Query("SELECT h FROM PayoutHoldEntity h WHERE h.userId = :userId AND h.reason = :reason AND h.releasedAt IS NULL")
    Optional<PayoutHoldEntity> findActive(@Param("userId") UUID userId, @Param("reason") PayoutHoldReason reason);

    @Query("SELECT h FROM PayoutHoldEntity h WHERE h.userId IN :userIds AND h.releasedAt IS NULL ORDER BY h.heldSince")
    List<PayoutHoldEntity> findActiveByUserIds(@Param("userIds") Collection<UUID> userIds);
}
