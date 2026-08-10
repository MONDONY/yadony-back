package com.yadony.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KycRepository extends JpaRepository<KycVerificationEntity, UUID> {

    Optional<KycVerificationEntity> findByUserId(UUID userId);

    Optional<KycVerificationEntity> findByStripeVerificationSessionId(String sessionId);

    List<KycVerificationEntity> findByStatusAndStripeVerificationSessionIdIsNotNullAndCreatedAtBefore(
            KycVerificationStatus status, LocalDateTime createdBefore);
}
