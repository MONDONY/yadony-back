package com.yadony.api.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportReplyTemplateRepository extends JpaRepository<SupportReplyTemplateEntity, UUID> {

    List<SupportReplyTemplateEntity> findAllByOrderBySortOrderAscCreatedAtAsc();
}
