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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Pousse les nouveautés du fil admin vers les appareils enregistrés. Sondage toutes les 30 s :
 * pour chaque appareil, toute entrée plus récente que son curseur (et lisible avec les
 * permissions de l'admin) part une fois, puis le curseur avance. Un envoi en erreur laisse le
 * curseur en place : il sera retenté au passage suivant ; après une série de refus, l'appareil est
 * retiré (voir {@link #MAX_CONSECUTIVE_ERRORS}).
 *
 * <p>Le curseur effectif ne remonte jamais au-delà de {@link #MAX_AGE} : un jeton repris, un
 * admin réactivé ou un appareil sorti d'une série d'erreurs ne reçoit pas des entrées vieilles
 * de plusieurs jours. Un appareil sans nouveauté n'est pas écrit en base ; seuls les appareils
 * dont le curseur avance le sont, par un UPDATE ciblé.
 */
@Component
@ConditionalOnProperty(name = "yadony.admin-push.enabled", havingValue = "true", matchIfMissing = true)
public class AdminPushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AdminPushDispatcher.class);

    /** Au plus ce nombre de push par appareil et par passage : un rattrapage ne doit pas inonder. */
    static final int MAX_PER_DEVICE = 5;
    private static final int FEED_SIZE = 50;
    static final Duration LOOKBACK = Duration.ofSeconds(45);
    /**
     * Âge maximal d'une entrée poussée : plancher du curseur, quel que soit {@code last_pushed_at}.
     * Une notification de plus d'un quart d'heure n'a plus rien d'urgent, la cloche la garde.
     */
    static final Duration MAX_AGE = Duration.ofMinutes(15);
    /** Refus d'envoi consécutifs avant retrait de l'appareil : 20 passages de 30 s, environ 10 min. */
    static final int MAX_CONSECUTIVE_ERRORS = 20;
    private static final String TICKET_PARAM = "ticket=";

    private final AdminDeviceRepository devices;
    private final AdminUserRepository admins;
    private final AdminNotificationService notifications;
    private final AdminPushSender sender;
    private Clock clock = Clock.systemUTC();

    /**
     * Identifiants déjà poussés, par appareil. Le curseur recule de {@link #LOOKBACK} pour ne pas
     * perdre une entrée d'un type dont le cache (15 s, expiration indépendante par type) est en
     * retard ; ce cache évite de la renvoyer. Il dure plus que {@link #MAX_AGE} : un appareil au
     * repos n'est plus écrit, son curseur peut rester en deçà d'une entrée déjà poussée, et seul le
     * plancher l'écarte ensuite. Il vit en mémoire : un redémarrage peut renvoyer les entrées créées
     * dans les 45 s qui précèdent le dernier passage ayant poussé (5 par appareil et par passage au
     * plus). Horloge du répartiteur, pour que les tests fassent avancer le temps.
     */
    private final Cache<String, Boolean> sentIds = Caffeine.newBuilder()
            .ticker(this::nanos)
            .expireAfterWrite(MAX_AGE.plusMinutes(5)).maximumSize(10_000).build();

    /** Séries d'échecs par appareil, remises à zéro au premier envoi réussi (voir {@link #recordFailure}). */
    private final Cache<String, FailureStreak> failures = Caffeine.newBuilder()
            .ticker(this::nanos)
            .expireAfterAccess(Duration.ofHours(1)).maximumSize(10_000).build();

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

    private long nanos() {
        return TimeUnit.MILLISECONDS.toNanos(clock.instant().toEpochMilli());
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
        Instant floor = pollStart.minus(MAX_AGE);
        int sent = 0;
        for (AdminDeviceEntity device : adminDevices) {
            Instant stored = device.getLastPushedAt();
            Instant startCursor = stored.isAfter(floor) ? stored : floor;
            String deviceKey = device.getId() != null ? device.getId().toString() : device.getFcmToken();
            List<AdminNotificationItem> fresh = feed.stream()
                    .filter(i -> i.createdAt().isAfter(startCursor))
                    .filter(i -> sentIds.getIfPresent(deviceKey + ":" + i.id()) == null)
                    .sorted(Comparator.comparing(AdminNotificationItem::createdAt))
                    .toList();
            if (fresh.isEmpty()) {
                continue; // au repos : rien à envoyer, rien à écrire
            }
            boolean complete = fresh.size() <= MAX_PER_DEVICE;
            boolean stopped = false;
            boolean removed = false;
            Instant cursor = null; // null : le curseur ne bouge pas
            for (AdminNotificationItem item : fresh.stream().limit(MAX_PER_DEVICE).toList()) {
                AdminPushSender.SendOutcome outcome = sender.send(device.getFcmToken(),
                        AdminPushCopy.titleFor(item.type()), AdminPushCopy.BODY, payload(item));
                if (outcome.result() == AdminPushSender.SendResult.OK) {
                    sent++;
                    sentIds.put(deviceKey + ":" + item.id(), Boolean.TRUE);
                    failures.invalidate(deviceKey);
                    if (cursor == null || item.createdAt().isAfter(cursor)) {
                        cursor = item.createdAt();
                    }
                } else {
                    if (outcome.result() == AdminPushSender.SendResult.INVALID_TOKEN) {
                        log.warn("[AdminPush] Jeton non enregistré, appareil {} retiré", deviceKey);
                        softDelete(device, deviceKey);
                        removed = true;
                    } else {
                        removed = recordFailure(device, deviceKey, outcome);
                    }
                    stopped = true;
                    break;
                }
            }
            if (!stopped && complete) {
                Instant lookbackCursor = pollStart.minus(LOOKBACK);
                cursor = startCursor.isAfter(lookbackCursor) ? startCursor : lookbackCursor;
            }
            if (!removed && cursor != null && cursor.isAfter(stored)) {
                devices.advanceLastPushedAt(device.getId(), adminId, cursor);
            }
        }
        return sent;
    }

    /**
     * Compte l'échec dans la série de l'appareil. Seul le premier d'une série part en ERROR (Sentry
     * n'en retient pas d'autre) : une erreur permanente retentée toutes les 30 s ferait sinon 2 880
     * événements par jour. Après {@link #MAX_CONSECUTIVE_ERRORS} refus d'envoi ({@code ERROR},
     * environ 10 min), l'appareil est retiré ; une panne côté FCM ({@code SERVICE_ERROR}) ne
     * compte pas pour le retrait.
     *
     * @return vrai si l'appareil vient d'être retiré
     */
    private boolean recordFailure(AdminDeviceEntity device, String deviceKey, AdminPushSender.SendOutcome outcome) {
        FailureStreak streak = failures.get(deviceKey, k -> new FailureStreak());
        streak.failures++;
        if (outcome.result() == AdminPushSender.SendResult.ERROR) {
            streak.sendErrors++;
        }
        if (streak.failures == 1) {
            log.error("[AdminPush] Envoi échoué (appareil {}, {}) : {}", deviceKey, outcome.result(), outcome.detail());
        } else {
            log.warn("[AdminPush] Envoi toujours en échec (appareil {}, {}, {} échecs consécutifs) : {}",
                    deviceKey, outcome.result(), streak.failures, outcome.detail());
        }
        if (streak.sendErrors < MAX_CONSECUTIVE_ERRORS) {
            return false;
        }
        log.warn("[AdminPush] Appareil {} retiré après {} échecs d'envoi consécutifs", deviceKey, streak.sendErrors);
        softDelete(device, deviceKey);
        return true;
    }

    /** UPDATE ciblé : sans effet si l'appareil a déjà été supprimé entre-temps. */
    private void softDelete(AdminDeviceEntity device, String deviceKey) {
        failures.invalidate(deviceKey);
        devices.softDeleteActive(device.getId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** Série d'échecs en cours d'un appareil ; le répartiteur tourne sur un seul fil (fixedDelay). */
    private static final class FailureStreak {
        private int failures;
        private int sendErrors;
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
