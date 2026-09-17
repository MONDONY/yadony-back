package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PackageRequestRepository
        extends JpaRepository<PackageRequestEntity, UUID>, JpaSpecificationExecutor<PackageRequestEntity> {

    Page<PackageRequestEntity> findBySenderIdOrderByCreatedAtDesc(UUID senderId, Pageable pageable);

    long countBySenderIdAndStatusIn(UUID senderId, List<PackageRequestStatus> statuses);

    /** Brouillons d'un expéditeur — plafonné par yadony.limits.drafts (free/PRO). */
    long countBySenderIdAndStatus(UUID senderId, PackageRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PackageRequestEntity p WHERE p.id = :id AND p.deletedAt IS NULL")
    java.util.Optional<PackageRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT p FROM PackageRequestEntity p
        WHERE p.status IN ('OPEN', 'NEGOTIATING')
          AND p.desiredDate < :cutoffDate
    """)
    List<PackageRequestEntity> findExpired(@Param("cutoffDate") LocalDate cutoffDate);

    @Query("""
        SELECT p FROM PackageRequestEntity p
        WHERE LOWER(p.departureCity) = LOWER(:departureCity)
          AND LOWER(p.arrivalCity) = LOWER(:arrivalCity)
          AND p.status = 'OPEN'
    """)
    List<PackageRequestEntity> findOpenByCorridor(
            @Param("departureCity") String departureCity,
            @Param("arrivalCity") String arrivalCity);

    /**
     * Variante de {@link #findOpenByCorridor} incluant les demandes {@code NEGOTIATING},
     * alignée sur l'ensemble de statuts de la recherche standard
     * ({@code PackageRequestSpecifications.openOnly}).
     *
     * <p>Méthode séparée <b>volontairement</b> : {@link #findOpenByCorridor} alimente le
     * digest d'alertes corridor ({@code AlertService}), qui ne doit notifier que des
     * demandes encore strictement ouvertes. Ne pas fusionner les deux.
     */
    @Query("""
        SELECT p FROM PackageRequestEntity p
        WHERE LOWER(p.departureCity) = LOWER(:departureCity)
          AND LOWER(p.arrivalCity) = LOWER(:arrivalCity)
          AND p.status IN ('OPEN', 'NEGOTIATING')
    """)
    List<PackageRequestEntity> findOpenOrNegotiatingByCorridor(
            @Param("departureCity") String departureCity,
            @Param("arrivalCity") String arrivalCity);

    /**
     * Incrémente le compteur de vues sans charger l'entité : un compteur ne doit ni
     * déclencher le versioning ni repousser {@code updated_at}, qui date la dernière
     * modification par l'expéditeur. COALESCE couvre les lignes antérieures à V260.
     */
    @Modifying
    @Transactional
    @Query("UPDATE PackageRequestEntity p SET p.viewCount = COALESCE(p.viewCount, 0) + 1 "
           + "WHERE p.id = :id")
    int incrementViewCount(@Param("id") UUID id);
}
