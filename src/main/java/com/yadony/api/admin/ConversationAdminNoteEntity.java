package com.yadony.api.admin;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

/** Note interne d'un administrateur sur une conversation : vue des seuls administrateurs (voir V353). */
@Entity
@Table(name = "conversation_admin_notes")
@Where(clause = "deleted_at IS NULL")
public class ConversationAdminNoteEntity extends BaseEntity {

    /** {@code conversations.id} (interne), pas l'identifiant Firestore. */
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** Pointe vers {@code admin_users} sans clé étrangère (voir V353). */
    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    public UUID getConversationId() { return conversationId; }

    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public UUID getAdminId() { return adminId; }

    public void setAdminId(UUID adminId) { this.adminId = adminId; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }
}
