package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.PackageRequestViewEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackageRequestViewRepository extends JpaRepository<PackageRequestViewEntity, UUID> {

    boolean existsByPackageRequestIdAndViewerId(UUID packageRequestId, UUID viewerId);

    long countByPackageRequestId(UUID packageRequestId);

    /** Personnes par demande, en une requête : lignes {@code [packageRequestId, count]}, sans les demandes jamais vues. */
    @Query("SELECT v.packageRequestId, COUNT(v) FROM PackageRequestViewEntity v "
           + "WHERE v.packageRequestId IN :ids GROUP BY v.packageRequestId")
    List<Object[]> countByPackageRequestIds(@Param("ids") Collection<UUID> ids);
}
