package com.yadony.api.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportPredefinedReplyRepository extends JpaRepository<SupportPredefinedReplyEntity, UUID> {

    List<SupportPredefinedReplyEntity> findByActiveTrueOrderBySortOrderAsc();
}
