package com.yadony.api.requests.entity;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

/**
 * Première consultation d'une demande par une personne, autre que son expéditeur.
 * Une seule ligne par couple (demande, personne), à côté du compteur d'ouvertures
 * {@code package_requests.view_count} qui, lui, compte chaque ouverture.
 */
@Entity
@Table(name = "package_request_views",
       uniqueConstraints = @UniqueConstraint(name = "uq_package_request_views",
                                             columnNames = {"package_request_id", "viewer_id"}))
@SQLRestriction("deleted_at IS NULL")
public class PackageRequestViewEntity extends BaseEntity {

    @Column(name = "package_request_id", nullable = false, updatable = false)
    private UUID packageRequestId;

    @Column(name = "viewer_id", nullable = false, updatable = false)
    private UUID viewerId;

    protected PackageRequestViewEntity() {}

    public PackageRequestViewEntity(UUID packageRequestId, UUID viewerId) {
        this.packageRequestId = packageRequestId;
        this.viewerId = viewerId;
    }

    public UUID getPackageRequestId() { return packageRequestId; }
    public UUID getViewerId() { return viewerId; }
}
