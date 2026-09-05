package com.yadony.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface KycRepository extends JpaRepository<KycVerificationEntity, UUID> {

    Optional<KycVerificationEntity> findByUserId(UUID userId);

    Optional<KycVerificationEntity> findByVerificationSessionId(String sessionId);
}
