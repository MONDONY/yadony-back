package com.yadony.api.cancellation.fee;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CancellationFeeRepository extends JpaRepository<CancellationFeeEntity, UUID> {

    Optional<CancellationFeeEntity> findByBidId(UUID bidId);

    /** Ligne verrouillée : deux règlements concurrents de la même retenue se sérialisent. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM CancellationFeeEntity f WHERE f.bidId = :bidId")
    Optional<CancellationFeeEntity> findByBidIdForUpdate(@Param("bidId") UUID bidId);

    /** Ligne verrouillée par son id : deux essais de versement concurrents se sérialisent sur la transition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM CancellationFeeEntity f WHERE f.id = :id")
    Optional<CancellationFeeEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Ligne non supprimée par son id, sans verrou (requête JPQL : le filtre {@code @Where} s'y applique). */
    @Query("SELECT f FROM CancellationFeeEntity f WHERE f.id = :id")
    Optional<CancellationFeeEntity> findActiveById(@Param("id") UUID id);

    /**
     * Versements à reprendre : toutes les lignes {@code pending}, et les lignes {@code retained} réglées
     * (dernière écriture) avant {@code retainedBefore}. Les plus anciennes d'abord.
     */
    @Query("""
            SELECT f.id FROM CancellationFeeEntity f
            WHERE f.status = :pending
               OR (f.status = :retained AND f.updatedAt < :retainedBefore)
            ORDER BY f.updatedAt
            """)
    List<UUID> findPayoutRetryIds(@Param("pending") CancellationFeeStatus pending,
                                  @Param("retained") CancellationFeeStatus retained,
                                  @Param("retainedBefore") LocalDateTime retainedBefore);
}
