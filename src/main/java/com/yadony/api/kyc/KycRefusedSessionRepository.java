package com.yadony.api.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KycRefusedSessionRepository extends JpaRepository<KycRefusedSessionEntity, UUID> {

    boolean existsBySessionId(String sessionId);
}
