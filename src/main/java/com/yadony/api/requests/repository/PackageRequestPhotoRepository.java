package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.PackageRequestPhotoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageRequestPhotoRepository extends JpaRepository<PackageRequestPhotoEntity, UUID> {

    List<PackageRequestPhotoEntity> findByPackageRequestIdOrderByPositionAsc(UUID packageRequestId);

    Optional<PackageRequestPhotoEntity> findFirstByPackageRequestIdOrderByPositionAsc(UUID packageRequestId);

    void deleteByPackageRequestId(UUID packageRequestId);

    /** Batch-load photos for multiple package requests ordered by packageRequestId, position. */
    List<PackageRequestPhotoEntity> findByPackageRequestIdInOrderByPositionAsc(Collection<UUID> packageRequestIds);

    /** Demandes ayant au moins une photo non purgée (lecture sous @SQLRestriction : purged_at IS NULL). */
    @Query("SELECT DISTINCT p.packageRequestId FROM PackageRequestPhotoEntity p")
    List<UUID> findRequestIdsWithLivePhotos();

    /** Rapprochement R2 : clés des photos non purgées (lecture sous @SQLRestriction : purged_at IS NULL). */
    @Query("SELECT p.objectKey FROM PackageRequestPhotoEntity p WHERE p.objectKey LIKE CONCAT(:prefix, '%') "
            + "OR p.objectKey LIKE CONCAT('%/', :prefix, '%')")
    List<String> findLiveObjectKeyValuesMatching(@Param("prefix") String prefix);
}
