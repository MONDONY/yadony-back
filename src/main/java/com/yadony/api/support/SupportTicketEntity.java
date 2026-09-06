package com.yadony.api.support;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "support_tickets")
@Where(clause = "deleted_at IS NULL")
public class SupportTicketEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SupportTicketStatus status = SupportTicketStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private SupportPriority priority = SupportPriority.NORMAL;

    /**
     * Identifiant dans {@code admin_users}, sans contrainte de cle etrangere :
     * les comptes du back-office sont un referentiel separe de {@code users}, et
     * un admin desactive ne doit pas rendre illisible un ticket historique.
     */
    @Column(name = "assigned_admin_id")
    private UUID assignedAdminId;

    /**
     * Date du dernier message du fil, cote utilisateur ou cote admin. Distincte
     * de {@code updated_at}, qui bouge aussi sur une simple reassignation : c'est
     * cette colonne qui trie la file du support.
     */
    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * Date de derniere ouverture du fil par l'utilisateur. NULL = jamais
     * ouvert. Ne bouge pas le statut : lire n'est pas repondre.
     */
    @Column(name = "user_last_read_at")
    private LocalDateTime userLastReadAt;

    public UUID getUserId() { return userId; }

    public void setUserId(UUID userId) { this.userId = userId; }

    public String getCategory() { return category; }

    public void setCategory(String category) { this.category = category; }

    public String getSubject() { return subject; }

    public void setSubject(String subject) { this.subject = subject; }

    public SupportTicketStatus getStatus() { return status; }

    public void setStatus(SupportTicketStatus status) { this.status = status; }

    public SupportPriority getPriority() { return priority; }

    public void setPriority(SupportPriority priority) { this.priority = priority; }

    public UUID getAssignedAdminId() { return assignedAdminId; }

    public void setAssignedAdminId(UUID assignedAdminId) { this.assignedAdminId = assignedAdminId; }

    public LocalDateTime getLastMessageAt() { return lastMessageAt; }

    public void setLastMessageAt(LocalDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }

    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public LocalDateTime getUserLastReadAt() { return userLastReadAt; }

    public void setUserLastReadAt(LocalDateTime userLastReadAt) { this.userLastReadAt = userLastReadAt; }

    public boolean isResolved() { return status == SupportTicketStatus.RESOLVED; }
}
