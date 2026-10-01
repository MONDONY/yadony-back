package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.PackageRequestViewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PackageRequestViewRepository extends JpaRepository<PackageRequestViewEntity, UUID> {

    boolean existsByPackageRequestIdAndViewerId(UUID packageRequestId, UUID viewerId);

    long countByPackageRequestId(UUID packageRequestId);
}
