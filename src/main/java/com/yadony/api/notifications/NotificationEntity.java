package com.yadony.api.notifications;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Where(clause = "deleted_at IS NULL")
public class NotificationEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb")
    private Map<String, String> data;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "is_critical", nullable = false)
    private boolean isCritical = false;

    @Column(name = "acked_at")
    private LocalDateTime ackedAt;

    @Column(name = "sms_sent_at")
    private LocalDateTime smsSentAt;

    // ── Contrat de forme (refonte du sheet, 2026-09) ─────────────────────────
    // Ces trois champs sont DÉRIVÉS du type et des données au moment de la
    // construction, jamais fournis par l'appelant : un point d'émission ne peut
    // donc pas les oublier ni les contredire. Seul fullBody se pose à part.

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private NotificationCategory category;

    @Column(name = "group_key", length = 120)
    private String groupKey;

    @Column(name = "deeplink")
    private String deeplink;

    /** Texte complet d'une annonce plateforme. Nul partout ailleurs : le texte vit dans la ressource cible. */
    @Column(name = "full_body", columnDefinition = "TEXT")
    private String fullBody;

    protected NotificationEntity() {}

    public NotificationEntity(UUID userId, String type, String title, String body,
                              Map<String, String> data, boolean isCritical) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.data = data;
        this.isCritical = isCritical;
        this.category = NotificationCategory.fromType(type);
        this.groupKey = NotificationGroupKey.of(type, data).orElse(null);
        this.deeplink = NotificationDeeplink.of(type, data).orElse(null);
    }

    public UUID getUserId()              { return userId; }
    public String getType()              { return type; }
    public String getTitle()             { return title; }
    public String getBody()              { return body; }
    public Map<String, String> getData() { return data; }
    public LocalDateTime getReadAt()     { return readAt; }
    public boolean isRead()              { return readAt != null; }
    public boolean isCritical()          { return isCritical; }
    public LocalDateTime getAckedAt()    { return ackedAt; }
    public LocalDateTime getSmsSentAt()  { return smsSentAt; }
    public NotificationCategory getCategory() { return category; }
    public String getDeeplink()          { return deeplink; }
    public String getFullBody()          { return fullBody; }

    /**
     * Clé d'agrégation servie à l'app. Une notification sans clé partagée reste
     * seule dans son groupe, identifié par son propre id ; la colonne reste
     * nulle en base pour ne pas y écrire une valeur qui n'apporte rien.
     */
    public String getGroupKey() {
        if (groupKey != null) return groupKey;
        return getId() != null ? "notif:" + getId() : null;
    }

    public boolean hasSharedGroupKey() { return groupKey != null; }

    public void markRead(LocalDateTime at)    { this.readAt = at; }
    public void markAcked(LocalDateTime at)   { this.ackedAt = at; }
    public void markSmsSent(LocalDateTime at) { this.smsSentAt = at; }

    /** Réservé aux annonces : pose le corps court et garde le texte complet à côté. */
    void summarize(String shortBody, String fullBody) {
        this.body = shortBody;
        this.fullBody = fullBody;
    }
}
