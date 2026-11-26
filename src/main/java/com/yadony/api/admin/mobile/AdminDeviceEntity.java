package com.yadony.api.admin.mobile;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.Instant;
import java.util.UUID;

/** Appareil d'un administrateur (PWA mobile) abonné aux notifications push (voir V401). */
@Entity
@Table(name = "admin_devices")
@Where(clause = "deleted_at IS NULL")
public class AdminDeviceEntity extends BaseEntity {

    /** Pointe vers {@code admin_users} sans clé étrangère (voir V401). */
    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Column(name = "fcm_token", nullable = false, length = 512, unique = true)
    private String fcmToken;

    @Column(name = "device_name", length = 255)
    private String deviceName;

    @Column(name = "platform", length = 10)
    private String platform;

    @Column(name = "last_pushed_at", nullable = false)
    private Instant lastPushedAt = Instant.now();

    public UUID getAdminId() { return adminId; }

    public void setAdminId(UUID adminId) { this.adminId = adminId; }

    public String getFcmToken() { return fcmToken; }

    public void setFcmToken(String fcmToken) { this.fcmToken = fcmToken; }

    public String getDeviceName() { return deviceName; }

    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getPlatform() { return platform; }

    public void setPlatform(String platform) { this.platform = platform; }

    public Instant getLastPushedAt() { return lastPushedAt; }

    public void setLastPushedAt(Instant lastPushedAt) { this.lastPushedAt = lastPushedAt; }
}
