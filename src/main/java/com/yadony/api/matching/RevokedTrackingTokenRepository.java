package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RevokedTrackingTokenRepository extends JpaRepository<RevokedTrackingTokenEntity, UUID> {

    boolean existsByToken(String token);

    List<RevokedTrackingTokenEntity> findByBidId(UUID bidId);
}
