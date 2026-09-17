package com.yadony.api.requests.entity;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/** Un expéditeur a invité le voyageur d'un trajet à répondre à sa demande. */
@Entity
@Table(name = "package_request_invitations")
@SQLRestriction("deleted_at IS NULL")
public class PackageRequestInvitationEntity extends BaseEntity {

    @Column(name = "package_request_id", nullable = false, updatable = false)
    private UUID packageRequestId;

    @Column(name = "announcement_id", nullable = false, updatable = false)
    private UUID announcementId;

    @Column(name = "traveler_id", nullable = false, updatable = false)
    private UUID travelerId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private UUID senderId;

    protected PackageRequestInvitationEntity() {}

    public PackageRequestInvitationEntity(UUID packageRequestId, UUID announcementId,
                                          UUID travelerId, UUID senderId) {
        this.packageRequestId = packageRequestId;
        this.announcementId = announcementId;
        this.travelerId = travelerId;
        this.senderId = senderId;
    }

    public UUID getPackageRequestId() { return packageRequestId; }
    public UUID getAnnouncementId() { return announcementId; }
    public UUID getTravelerId() { return travelerId; }
    public UUID getSenderId() { return senderId; }
}
