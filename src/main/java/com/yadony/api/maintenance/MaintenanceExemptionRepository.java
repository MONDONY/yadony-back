package com.yadony.api.maintenance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaintenanceExemptionRepository extends JpaRepository<MaintenanceExemptionEntity, UUID> {

    Optional<MaintenanceExemptionEntity> findByUserId(UUID userId);

    List<MaintenanceExemptionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * UID Firebase des comptes exemptés : c'est ce que le filtre connaît de l'appelant
     * (le principal d'un utilisateur est son UID). Natif pour lire {@code users} sans
     * dépendre de l'entité d'un autre package.
     */
    @Query(value = """
            SELECT u.firebase_uid FROM maintenance_exemptions e
            JOIN users u ON u.id = e.user_id
            WHERE e.deleted_at IS NULL AND u.deleted_at IS NULL
            """, nativeQuery = true)
    List<String> findExemptFirebaseUids();
}
