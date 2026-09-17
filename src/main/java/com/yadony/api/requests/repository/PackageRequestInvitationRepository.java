package com.yadony.api.requests.repository;

import com.yadony.api.requests.entity.PackageRequestInvitationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageRequestInvitationRepository
        extends JpaRepository<PackageRequestInvitationEntity, UUID> {

    Optional<PackageRequestInvitationEntity> findByPackageRequestIdAndAnnouncementId(
            UUID packageRequestId, UUID announcementId);

    List<PackageRequestInvitationEntity> findByPackageRequestIdOrderByCreatedAtAsc(UUID packageRequestId);

    long countByPackageRequestId(UUID packageRequestId);
}
