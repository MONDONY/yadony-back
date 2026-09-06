package com.yadony.api.support;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

@Entity
@Table(name = "support_messages")
@Where(clause = "deleted_at IS NULL")
public class SupportMessageEntity extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false, length = 16)
    private SupportMessageAuthorType authorType;

    /** Pointe vers {@code users} ou {@code admin_users} selon {@link #authorType}. */
    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    public UUID getTicketId() { return ticketId; }

    public void setTicketId(UUID ticketId) { this.ticketId = ticketId; }

    public SupportMessageAuthorType getAuthorType() { return authorType; }

    public void setAuthorType(SupportMessageAuthorType authorType) { this.authorType = authorType; }

    public UUID getAuthorId() { return authorId; }

    public void setAuthorId(UUID authorId) { this.authorId = authorId; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }
}
