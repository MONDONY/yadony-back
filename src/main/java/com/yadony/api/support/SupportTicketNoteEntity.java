package com.yadony.api.support;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

/** Note interne d'un agent sur un ticket : vue des seuls administrateurs, jamais de l'utilisateur. */
@Entity
@Table(name = "support_ticket_notes")
@Where(clause = "deleted_at IS NULL")
public class SupportTicketNoteEntity extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    /** Pointe vers {@code admin_users} sans clé étrangère (voir V350). */
    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    public UUID getTicketId() { return ticketId; }

    public void setTicketId(UUID ticketId) { this.ticketId = ticketId; }

    public UUID getAdminId() { return adminId; }

    public void setAdminId(UUID adminId) { this.adminId = adminId; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }
}
