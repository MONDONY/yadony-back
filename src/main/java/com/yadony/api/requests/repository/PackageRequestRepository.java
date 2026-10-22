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

    /** Demandes ouvertes d'un expéditeur, les plus récemment créées d'abord (section « En cours » de son profil). */
    @Query("""
        SELECT p FROM PackageRequestEntity p
        WHERE p.senderId = :senderId AND p.status IN :statuses
        ORDER BY COALESCE(p.disclaimerSignedAt, p.createdAt) DESC
    """)
    List<PackageRequestEntity> findOpenBySenderId(@Param("senderId") UUID senderId,
                                                  @Param("statuses") java.util.Collection<PackageRequestStatus> statuses,
                                                  Pageable pageable);

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

    /**
     * Demandes « Date flexible » (sans date, V328) ouvertes depuis trop longtemps :
     * publiées avant {@code publishedBefore}. La publication est {@code disclaimerSignedAt}
     * (posée à la création directe comme à la publication d'un brouillon), repli sur
     * {@code createdAt} : un vieux brouillon publié aujourd'hui ne doit pas expirer demain.
     * Sans date, {@link #findExpired} ne les voit jamais.
     */
    @Query("""
        SELECT p FROM PackageRequestEntity p
        WHERE p.status IN ('OPEN', 'NEGOTIATING')
          AND p.desiredDate IS NULL
          AND COALESCE(p.disclaimerSignedAt, p.createdAt) < :publishedBefore
    """)
    List<PackageRequestEntity> findFlexibleExpired(@Param("publishedBefore") java.time.LocalDateTime publishedBefore);

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
     * modification par l'expéditeur. COALESCE couvre les lignes antérieures à V261.
     */
    @Modifying
    @Transactional
    @Query("UPDATE PackageRequestEntity p SET p.viewCount = COALESCE(p.viewCount, 0) + 1 "
           + "WHERE p.id = :id")
    int incrementViewCount(@Param("id") UUID id);

    /**
     * Rapprochement R2 : ancienne photo unique des demandes, saisie libre du client (n'importe
     * quel préfixe). Requête native : une demande supprimée logiquement garde sa photo.
     */
    @Query(value = "SELECT photo_url FROM package_requests WHERE photo_url LIKE CONCAT(:prefix, '%') "
            + "OR photo_url LIKE CONCAT('%/', :prefix, '%')", nativeQuery = true)
    List<String> findPhotoUrlValuesMatchingIncludingDeleted(@Param("prefix") String prefix);
}
