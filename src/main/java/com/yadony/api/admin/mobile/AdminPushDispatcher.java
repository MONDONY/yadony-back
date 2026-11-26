package com.yadony.api.admin.mobile;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPermissions;
import com.yadony.api.admin.account.AdminStatus;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.notifications.AdminNotificationItem;
import com.yadony.api.admin.notifications.AdminNotificationService;
import com.yadony.api.admin.notifications.AdminNotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pousse les nouveautés du fil admin vers les appareils enregistrés. Sondage toutes les 30 s :
 * pour chaque appareil, toute entrée plus récente que {@code last_pushed_at} (et lisible avec les
 * permissions de l'admin) part une fois, puis le curseur avance. Un envoi en erreur laisse le
 * curseur en place : il sera retenté au passage suivant.
 */
@Component
@ConditionalOnProperty(name = "yadony.admin-push.enabled", havingValue = "true", matchIfMissing = true)
public class AdminPushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AdminPushDispatcher.class);

    /** Au plus ce nombre de push par appareil et par passage : un rattrapage ne doit pas inonder. */
    static final int MAX_PER_DEVICE = 5;
    private static final int FEED_SIZE = 50;

    private final AdminDeviceRepository devices;
    private final AdminUserRepository admins;
    private final AdminNotificationService notifications;
    private final AdminPushSender sender;

    public AdminPushDispatcher(AdminDeviceRepository devices, AdminUserRepository admins,
                               AdminNotificationService notifications, AdminPushSender sender) {
        this.devices = devices;
        this.admins = admins;
        this.notifications = notifications;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${yadony.admin-push.interval-ms:30000}")
    public void poll() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("[AdminPush] Passage interrompu : {}", e.getMessage());
        }
    }

    /** @return nombre de push envoyés avec succès */
    public int runOnce() {
        Map<UUID, List<AdminDeviceEntity>> byAdmin = new LinkedHashMap<>();
        for (AdminDeviceEntity d : devices.findActive()) {
            byAdmin.computeIfAbsent(d.getAdminId(), k -> new java.util.ArrayList<>()).add(d);
        }
        int total = 0;
        for (Map.Entry<UUID, List<AdminDeviceEntity>> e : byAdmin.entrySet()) {
            try {
                total += dispatchForAdmin(e.getKey(), e.getValue());
            } catch (RuntimeException ex) {
                log.warn("[AdminPush] Admin ignoré ce passage : {}", ex.getMessage());
            }
        }
        return total;
    }

    private int dispatchForAdmin(UUID adminId, List<AdminDeviceEntity> adminDevices) {
        AdminUserEntity admin = admins.findById(adminId).orElse(null);
        if (admin == null || admin.getStatus() != AdminStatus.ACTIVE) {
            return 0;
        }
        Set<AdminPermission> permissions = AdminPermissions.effective(admin.getRole(), admin.getPermissionOverrides());
        List<AdminNotificationItem> feed = notifications.feed(adminId, permissions, null, FEED_SIZE).items();
        int sent = 0;
        for (AdminDeviceEntity device : adminDevices) {
            Instant cursor = device.getLastPushedAt();
            List<AdminNotificationItem> fresh = feed.stream()
                    .filter(i -> i.createdAt().isAfter(cursor))
                    .sorted(Comparator.comparing(AdminNotificationItem::createdAt).reversed())
                    .toList();
            if (fresh.isEmpty()) {
                continue;
            }
            boolean failed = false;
            boolean removed = false;
            for (AdminNotificationItem item : fresh.stream().limit(MAX_PER_DEVICE).toList()) {
                AdminPushSender.SendResult result = sender.send(device.getFcmToken(),
                        AdminPushCopy.titleFor(item.type()), AdminPushCopy.BODY, payload(item));
                if (result == AdminPushSender.SendResult.OK) {
                    sent++;
                } else if (result == AdminPushSender.SendResult.INVALID_TOKEN) {
                    device.softDelete();
                    devices.save(device);
                    removed = true;
                    break;
                } else {
                    failed = true;
                    break;
                }
            }
            if (!removed && !failed) {
                device.setLastPushedAt(fresh.get(0).createdAt());
                devices.save(device);
            }
        }
        return sent;
    }

    static Map<String, String> payload(AdminNotificationItem item) {
        return Map.of("type", item.type().name(), "route", routeFor(item));
    }

    static String routeFor(AdminNotificationItem item) {
        String entityId = item.id().substring(item.id().indexOf(':') + 1);
        AdminNotificationType type = item.type();
        return switch (type) {
            case ADMIN_ALERT -> "/m/alertes/" + entityId;
            case SUPPORT_TICKET_CREATED, SUPPORT_MESSAGE_RECEIVED -> "/m/support/" + entityId;
            default -> "/m/alertes";
        };
    }
}
