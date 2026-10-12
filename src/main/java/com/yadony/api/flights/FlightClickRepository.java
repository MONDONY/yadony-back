package com.yadony.api.flights;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FlightClickRepository extends JpaRepository<FlightClickEntity, UUID> {
}
