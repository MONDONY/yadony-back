package com.yadony.api.automation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AutomationRuleRepository extends JpaRepository<AutomationRuleEntity, UUID> {

    List<AutomationRuleEntity> findByTravelerIdOrderByCreatedAtAsc(UUID travelerId);

    List<AutomationRuleEntity> findByTravelerIdAndDisabledByDowngradeTrue(UUID travelerId);

    @Query("SELECT r FROM AutomationRuleEntity r WHERE r.id = :id AND r.travelerId = :travelerId")
    Optional<AutomationRuleEntity> findByIdAndTravelerId(@Param("id") UUID id,
                                                          @Param("travelerId") UUID travelerId);
}
