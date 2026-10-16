package com.yadony.api.assistant.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssistantLlmProviderRepository extends JpaRepository<AssistantLlmProviderEntity, UUID> {

    List<AssistantLlmProviderEntity> findAllByOrderByPriorityAsc();

    List<AssistantLlmProviderEntity> findByEnabledTrueOrderByPriorityAsc();

    boolean existsBySlug(String slug);
}
