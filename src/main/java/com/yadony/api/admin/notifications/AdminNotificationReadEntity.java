package com.yadony.api.admin.notifications;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/** Une notification du fil lue par un administrateur (voir V352). */
@Entity
@Table(name = "admin_notification_reads")
@Where(clause = "deleted_at IS NULL")
public class AdminNotificationReadEntity extends BaseEntity {

    /** Pointe vers {@code admin_users} sans clé étrangère (voir V352). */
    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    /** Identifiant du fil : {@code <TYPE>:<uuid>}. */
    @Column(name = "notification_id", nullable = false, length = 80)
    private String notificationId;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;

    public UUID getAdminId() { return adminId; }

    public void setAdminId(UUID adminId) { this.adminId = adminId; }

    public String getNotificationId() { return notificationId; }

    public void setNotificationId(String notificationId) { this.notificationId = notificationId; }

    public LocalDateTime getReadAt() { return readAt; }

    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
}
