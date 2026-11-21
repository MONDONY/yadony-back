package com.yadony.api.cancellation.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CancellationFeeRepository extends JpaRepository<CancellationFeeEntity, UUID> {

    Optional<CancellationFeeEntity> findByBidId(UUID bidId);

    List<CancellationFeeEntity> findByStatus(CancellationFeeStatus status);
}
