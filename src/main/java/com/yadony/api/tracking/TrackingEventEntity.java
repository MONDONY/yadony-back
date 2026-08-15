package com.yadony.api.tracking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "tracking_events")
public class TrackingEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "event_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private TrackingEventType eventType;

    @Column(name = "scanned_at", nullable = false)
    private LocalDateTime scannedAt;

    @Column(name = "gps_lat", precision = 10, scale = 8)
    private BigDecimal gpsLat;

    @Column(name = "gps_lon", precision = 11, scale = 8)
    private BigDecimal gpsLon;

    @Column(name = "gps_label", length = 255)
    private String gpsLabel;

    @Column(name = "photo_url", length = 1024)
    private String photoUrl;

    @Column(name = "offline_timestamp")
    private LocalDateTime offlineTimestamp;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(ZoneOffset.UTC);
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() { return id; }
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public TrackingEventType getEventType() { return eventType; }
    public void setEventType(TrackingEventType eventType) { this.eventType = eventType; }
    public LocalDateTime getScannedAt() { return scannedAt; }
    public void setScannedAt(LocalDateTime scannedAt) { this.scannedAt = scannedAt; }
    public BigDecimal getGpsLat() { return gpsLat; }
    public void setGpsLat(BigDecimal gpsLat) { this.gpsLat = gpsLat; }
    public BigDecimal getGpsLon() { return gpsLon; }
    public void setGpsLon(BigDecimal gpsLon) { this.gpsLon = gpsLon; }
    public String getGpsLabel() { return gpsLabel; }
    public void setGpsLabel(String gpsLabel) { this.gpsLabel = gpsLabel; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public LocalDateTime getOfflineTimestamp() { return offlineTimestamp; }
    public void setOfflineTimestamp(LocalDateTime offlineTimestamp) { this.offlineTimestamp = offlineTimestamp; }
    public LocalDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(LocalDateTime syncedAt) { this.syncedAt = syncedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
