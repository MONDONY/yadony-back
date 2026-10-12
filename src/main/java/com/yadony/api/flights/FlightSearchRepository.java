package com.yadony.api.flights;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FlightSearchRepository extends JpaRepository<FlightSearchEntity, UUID> {
}
