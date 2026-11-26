package com.yadony.api.admin.mobile;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPermissions;
import com.yadony.api.admin.account.AdminStatus;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.notifications.AdminNotificationItem;
import com.yadony.api.admin.notifications.AdminNotificationService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    static final Duration LOOKBACK = Duration.ofSeconds(45);
    private static final String TICKET_PARAM = "ticket=";

    private final AdminDeviceRepository devices;
    private final AdminUserRepository admins;
    private final AdminNotificationService notifications;
    private final AdminPushSender sender;
    private Clock clock = Clock.systemUTC();

    /**
     * Identifiants déjà poussés, par appareil. Le curseur recule de {@link #LOOKBACK} pour ne pas
     * perdre une entrée d'un type dont le cache (15 s, expiration indépendante par type) est en
     * retard ; ce cache évite de la renvoyer. Il vit en mémoire : un redémarrage peut renvoyer au
     * plus les 45 dernières secondes d'entrées.
     */
    private final Cache<String, Boolean> sentIds = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(10_000).build();

    public AdminPushDispatcher(AdminDeviceRepository devices, AdminUserRepository admins,
                               AdminNotificationService notifications, AdminPushSender sender) {
        this.devices = devices;
        this.admins = admins;
        this.notifications = notifications;
        this.sender = sender;
    }

    void setClock(Clock clock) {
        this.clock = clock;
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
            byAdmin.computeIfAbsent(d.getAdminId(), k -> new ArrayList<>()).add(d);
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
        Instant pollStart = clock.instant();
        int sent = 0;
        for (AdminDeviceEntity device : adminDevices) {
            Instant startCursor = device.getLastPushedAt();
            String deviceKey = device.getId() != null ? device.getId().toString() : device.getFcmToken();
            List<AdminNotificationItem> fresh = feed.stream()
                    .filter(i -> i.createdAt().isAfter(startCursor))
                    .filter(i -> sentIds.getIfPresent(deviceKey + ":" + i.id()) == null)
                    .sorted(Comparator.comparing(AdminNotificationItem::createdAt))
                    .toList();
            boolean complete = fresh.size() <= MAX_PER_DEVICE;
            boolean stopped = false;
            for (AdminNotificationItem item : fresh.stream().limit(MAX_PER_DEVICE).toList()) {
                AdminPushSender.SendResult result = sender.send(device.getFcmToken(),
                        AdminPushCopy.titleFor(item.type()), AdminPushCopy.BODY, payload(item));
                if (result == AdminPushSender.SendResult.OK) {
                    sent++;
                    sentIds.put(deviceKey + ":" + item.id(), Boolean.TRUE);
                    if (item.createdAt().isAfter(device.getLastPushedAt())) {
                        device.setLastPushedAt(item.createdAt());
                    }
                } else {
                    if (result == AdminPushSender.SendResult.INVALID_TOKEN) {
                        device.softDelete();
                    }
                    stopped = true;
                    break;
                }
            }
            if (!stopped && complete) {
                Instant lookbackCursor = pollStart.minus(LOOKBACK);
                device.setLastPushedAt(startCursor.isAfter(lookbackCursor) ? startCursor : lookbackCursor);
            }
            devices.save(device);
        }
        return sent;
    }

    static Map<String, String> payload(AdminNotificationItem item) {
        return Map.of("type", item.type().name(), "route", routeFor(item));
    }

    /**
     * Page mobile ouverte au tap. Une alerte ouvre sa fiche ; un ticket ou un message support ouvre
     * le ticket ; les autres types ouvrent la liste des alertes sur l'entrée ({@code ?item=} reprend
     * l'id du fil, sans donnée personnelle).
     */
    static String routeFor(AdminNotificationItem item) {
        return switch (item.type()) {
            case ADMIN_ALERT -> "/m/alertes/" + item.id().substring(item.id().indexOf(':') + 1);
            // L'id d'un message support est celui du message : le ticket n'est porté que par le lien.
            case SUPPORT_TICKET_CREATED, SUPPORT_MESSAGE_RECEIVED -> ticketIdIn(item.link())
                    .map(ticket -> "/m/support/" + ticket)
                    .orElse("/m/support");
            default -> "/m/alertes?item=" + item.id();
        };
    }

    /** Ticket du lien {@code /support?ticket=<uuid>} ; vide si absent ou illisible. */
    private static Optional<UUID> ticketIdIn(String link) {
        if (link == null) {
            return Optional.empty();
        }
        int start = link.indexOf(TICKET_PARAM);
        if (start < 0) {
            return Optional.empty();
        }
        start += TICKET_PARAM.length();
        int end = link.indexOf('&', start);
        try {
            return Optional.of(UUID.fromString(end < 0 ? link.substring(start) : link.substring(start, end)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
