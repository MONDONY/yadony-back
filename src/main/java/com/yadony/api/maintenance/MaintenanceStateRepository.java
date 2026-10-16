package com.yadony.api.maintenance;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceStateRepository extends JpaRepository<MaintenanceStateEntity, Short> {
}
