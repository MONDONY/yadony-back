package com.yadony.api.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportTicketNoteRepository extends JpaRepository<SupportTicketNoteEntity, UUID> {

    List<SupportTicketNoteEntity> findByTicketIdOrderByCreatedAtDesc(UUID ticketId);
}
