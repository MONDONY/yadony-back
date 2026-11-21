package com.yadony.api.cancellation.fee;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CancellationFeeRepository extends JpaRepository<CancellationFeeEntity, UUID> {

    Optional<CancellationFeeEntity> findByBidId(UUID bidId);

    List<CancellationFeeEntity> findByStatus(CancellationFeeStatus status);

    /** Ligne verrouillée : deux règlements concurrents de la même retenue se sérialisent. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM CancellationFeeEntity f WHERE f.bidId = :bidId")
    Optional<CancellationFeeEntity> findByBidIdForUpdate(@Param("bidId") UUID bidId);
}
