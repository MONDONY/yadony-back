package com.yadony.api.notifications;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "user_notification_preferences")
public class NotificationPrefsEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "push_activity_bids", nullable = false)
    private boolean pushActivityBids = true;

    @Column(name = "push_activity_negotiations", nullable = false)
    private boolean pushActivityNegotiations = true;

    @Column(name = "push_messages", nullable = false)
    private boolean pushMessages = true;

    @Column(name = "push_trip_reminder", nullable = false)
    private boolean pushTripReminder = true;

    @Column(name = "push_promo", nullable = false)
    private boolean pushPromo = false;

    @Column(name = "push_corridor_alerts", nullable = false)
    private boolean pushCorridorAlerts = true;

    /** Notif temps réel « un colis matche un de mes trajets » (côté voyageur). */
    @Column(name = "push_trip_package_match", nullable = false)
    private boolean pushTripPackageMatch = true;

    /** Appels Yadony manqués (CALL_MISSED), V305. */
    @Column(name = "push_missed_calls", nullable = false)
    private boolean pushMissedCalls = true;

    /** Automatisations du voyageur (automation_*), V305. */
    @Column(name = "push_traveler_automations", nullable = false)
    private boolean pushTravelerAutomations = true;

    /** Rappels et conseils (premiers pas, « Bon voyage »), V305. */
    @Column(name = "push_reminders_tips", nullable = false)
    private boolean pushRemindersTips = true;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public boolean isPushActivityBids() { return pushActivityBids; }
    public void setPushActivityBids(boolean v) { this.pushActivityBids = v; }
    public boolean isPushActivityNegotiations() { return pushActivityNegotiations; }
    public void setPushActivityNegotiations(boolean v) { this.pushActivityNegotiations = v; }
    public boolean isPushMessages() { return pushMessages; }
    public void setPushMessages(boolean v) { this.pushMessages = v; }
    public boolean isPushTripReminder() { return pushTripReminder; }
    public void setPushTripReminder(boolean v) { this.pushTripReminder = v; }
    public boolean isPushPromo() { return pushPromo; }
    public void setPushPromo(boolean v) { this.pushPromo = v; }
    public boolean isPushCorridorAlerts() { return pushCorridorAlerts; }
    public void setPushCorridorAlerts(boolean v) { this.pushCorridorAlerts = v; }
    public boolean isPushTripPackageMatch() { return pushTripPackageMatch; }
    public void setPushTripPackageMatch(boolean v) { this.pushTripPackageMatch = v; }
    public boolean isPushMissedCalls() { return pushMissedCalls; }
    public void setPushMissedCalls(boolean v) { this.pushMissedCalls = v; }
    public boolean isPushTravelerAutomations() { return pushTravelerAutomations; }
    public void setPushTravelerAutomations(boolean v) { this.pushTravelerAutomations = v; }
    public boolean isPushRemindersTips() { return pushRemindersTips; }
    public void setPushRemindersTips(boolean v) { this.pushRemindersTips = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
