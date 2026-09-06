package com.yadony.api.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportMessageRepository extends JpaRepository<SupportMessageEntity, UUID> {

    List<SupportMessageEntity> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);
}
