package com.yadony.api.flights;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlightPriceAlertRepository extends JpaRepository<FlightPriceAlertEntity, UUID> {

    List<FlightPriceAlertEntity> findAllByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<FlightPriceAlertEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<FlightPriceAlertEntity> findAllByActiveTrue();

    /**
     * Résultat de la première vérification d'une alerte, écrit seulement si elle n'a encore jamais
     * été vérifiée et n'est pas supprimée : rien d'autre n'est touché (pause, seuil, anti-spam).
     *
     * @return 1 si l'alerte a été mise à jour, 0 sinon
     */
    @Transactional
    @Modifying
    @Query("UPDATE FlightPriceAlertEntity a SET a.lastSeenPrice = :lastSeenPrice, a.lastCheckedAt = :checkedAt,"
            + " a.lastMatchCount = :matchCount, a.lastMatchAirlines = :airlines"
            + " WHERE a.id = :id AND a.lastCheckedAt IS NULL AND a.deletedAt IS NULL")
    int recordFirstCheck(@Param("id") UUID id, @Param("lastSeenPrice") BigDecimal lastSeenPrice,
                         @Param("checkedAt") LocalDateTime checkedAt, @Param("matchCount") Integer matchCount,
                         @Param("airlines") String airlines);
}
