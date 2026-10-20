package com.yadony.api.kyc.erasure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface DiditSessionDeletionRepository extends JpaRepository<DiditSessionDeletionEntity, UUID> {

    boolean existsBySessionId(String sessionId);

    @Query("""
            SELECT d.id FROM DiditSessionDeletionEntity d
             WHERE d.status = com.yadony.api.kyc.erasure.DiditSessionDeletionEntity.Status.PENDING
               AND d.nextAttemptAt <= :now
             ORDER BY d.nextAttemptAt
            """)
    List<UUID> findDueIds(@Param("now") LocalDateTime now, Pageable page);

    @Query("""
            SELECT d.id FROM DiditSessionDeletionEntity d
             WHERE d.userId = :userId
               AND d.status = com.yadony.api.kyc.erasure.DiditSessionDeletionEntity.Status.PENDING
            """)
    List<UUID> findPendingIdsByUserId(@Param("userId") UUID userId);

    /**
     * Prend le bail d'un essai : repousse {@code nextAttemptAt} à {@code leaseUntil} si la ligne
     * est encore due. Rend 0 si un autre essai l'a déjà prise ou si elle est close.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE DiditSessionDeletionEntity d
               SET d.nextAttemptAt = :leaseUntil, d.lastAttemptAt = :now, d.updatedAt = :now
             WHERE d.id = :id
               AND d.status = com.yadony.api.kyc.erasure.DiditSessionDeletionEntity.Status.PENDING
               AND d.nextAttemptAt <= :now
            """)
    int claim(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);
}
