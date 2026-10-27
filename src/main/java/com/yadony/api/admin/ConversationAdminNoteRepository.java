package com.yadony.api.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationAdminNoteRepository extends JpaRepository<ConversationAdminNoteEntity, UUID> {

    List<ConversationAdminNoteEntity> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
