package com.yadony.api.admin.broadcast;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Lot D — une ligne d'historique par broadcast envoye. Jamais modifiee apres creation. */
@Entity
@Table(name = "admin_broadcasts")
@Where(clause = "deleted_at IS NULL")
public class AdminBroadcastEntity extends BaseEntity {

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private BroadcastTargetType targetType;

    @Column(name = "target_origin", length = 100)
    private String targetOrigin;

    @Column(name = "target_destination", length = 100)
    private String targetDestination;

    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    /** Règle copiée telle qu'envoyée (JSON canonique) ; {@code JSON} obligatoire, cf. AdminAlertEntity. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_rule", columnDefinition = "jsonb")
    private String audienceRule;

    @Column(name = "segment_id")
    private UUID segmentId;

    @Column(name = "segment_name", length = 80)
    private String segmentName;

    protected AdminBroadcastEntity() {
        // Hibernate
    }

    public AdminBroadcastEntity(String title, String body, BroadcastTargetType targetType,
                                String targetOrigin, String targetDestination, UUID targetUserId,
                                int recipientCount, UUID adminId) {
        this.title = title;
        this.body = body;
        this.targetType = targetType;
        this.targetOrigin = targetOrigin;
        this.targetDestination = targetDestination;
        this.targetUserId = targetUserId;
        this.recipientCount = recipientCount;
        this.adminId = adminId;
    }

    public AdminBroadcastEntity(String title, String body, BroadcastTargetType targetType,
                                String targetOrigin, String targetDestination, UUID targetUserId,
                                int recipientCount, UUID adminId,
                                String audienceRule, UUID segmentId, String segmentName) {
        this(title, body, targetType, targetOrigin, targetDestination, targetUserId, recipientCount, adminId);
        this.audienceRule = audienceRule;
        this.segmentId = segmentId;
        this.segmentName = segmentName;
    }

    public String getTitle() { return title; }
    public String getBody() { return body; }
    public BroadcastTargetType getTargetType() { return targetType; }
    public String getTargetOrigin() { return targetOrigin; }
    public String getTargetDestination() { return targetDestination; }
    public UUID getTargetUserId() { return targetUserId; }
    public int getRecipientCount() { return recipientCount; }
    public UUID getAdminId() { return adminId; }
    public String getAudienceRule() { return audienceRule; }
    public UUID getSegmentId() { return segmentId; }
    public String getSegmentName() { return segmentName; }
}
