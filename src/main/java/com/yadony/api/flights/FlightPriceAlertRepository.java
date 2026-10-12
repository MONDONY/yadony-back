package com.yadony.api.flights;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightPriceAlertRepository extends JpaRepository<FlightPriceAlertEntity, UUID> {

    List<FlightPriceAlertEntity> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    long countByOwnerId(UUID ownerId);

    Optional<FlightPriceAlertEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<FlightPriceAlertEntity> findAllByActiveTrue();
}
