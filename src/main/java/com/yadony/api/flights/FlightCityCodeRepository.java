package com.yadony.api.flights;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FlightCityCodeRepository extends JpaRepository<FlightCityCodeEntity, UUID> {

    Optional<FlightCityCodeEntity> findByCityKey(String cityKey);
}
