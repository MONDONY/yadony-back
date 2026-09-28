package com.yadony.api.admin.notifications;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.metrics.AdminQueueSnapshot;
import org.springframework.stereotype.Service;

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
import java.util.Set;
import java.util.UUID;

/**
 * Cloche du panel : fil des nouveautés, compteurs du menu et état lu/non lu par administrateur.
 *
 * <p>Lecture seule, sans trace dans {@code audit_log} : ces appels reviennent toutes les 30
 * secondes et n'engagent aucune décision. Même chose pour {@link #markSeen}.
 */
@Service
public class AdminNotificationService {

    /** Plafond du non-lu affiché ; au-delà, {@code unreadCapped} vaut vrai (« 99+ »). */
    static final int UNREAD_CAP = 99;
    static final int MAX_LIMIT = 100;
    /** Un administrateur qui n'a jamais ouvert la cloche voit la dernière semaine comme non lue. */
    static final Duration NEVER_SEEN_LOOKBACK = Duration.ofDays(7);

    private static final Comparator<AdminNotificationItem> NEWEST_FIRST =
            Comparator.comparing(AdminNotificationItem::createdAt).reversed()
                    .thenComparing(AdminNotificationItem::id);

    private final AdminNotificationQueries queries;
    private final AdminNotificationCache cache;
    private final AdminUserRepository admins;
    private Clock clock = Clock.systemUTC();

    public AdminNotificationService(AdminNotificationQueries queries, AdminNotificationCache cache,
                                    AdminUserRepository admins) {
        this.queries = queries;
        this.cache = cache;
        this.admins = admins;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Page du fil, du plus récent au plus ancien. Sans curseur, la première page vient du cache
     * partagé ; avec {@code before} (exclusif), chaque source visible est interrogée directement.
     */
    public AdminNotificationFeedResponse feed(UUID adminId, Set<AdminPermission> permissions, Instant before,
                                              int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Instant now = clock.instant();
        Instant from = now.minus(AdminNotificationCache.WINDOW);
        List<AdminNotificationType> visible = AdminNotificationType.visibleTo(permissions);

        List<AdminNotificationItem> merged = new ArrayList<>();
        if (before == null) {
            visible.forEach(type -> merged.addAll(cache.recent(type)));
        } else if (before.isAfter(from)) {
            visible.forEach(type -> merged.addAll(queries.find(type, from, before, size)));
        }
        List<AdminNotificationItem> page = merged.stream()
                .filter(item -> !item.createdAt().isBefore(from))
                .filter(item -> before == null || item.createdAt().isBefore(before))
                .sorted(NEWEST_FIRST)
                .limit(size)
                .toList();

        Instant lastSeen = lastSeen(adminId, now);
        Unread unread = unread(visible, lastSeen, from);
        return new AdminNotificationFeedResponse(page, unread.count(), unread.capped(), lastSeen);
    }

    /**
     * Éléments à traiter par entrée de menu. Une clé n'apparaît que si l'administrateur a la
     * permission de lire l'entrée ; sans aucune, les files ne sont même pas consultées.
     */
    public AdminNotificationCountersResponse counters(UUID adminId, Set<AdminPermission> permissions) {
        Map<String, Long> counts = new LinkedHashMap<>();
        boolean anyQueue = permissions.contains(AdminPermission.REPORT_VIEW)
                || permissions.contains(AdminPermission.SUPPORT_TICKET_VIEW)
                || permissions.contains(AdminPermission.DISPUTE_VIEW)
                || permissions.contains(AdminPermission.USER_KYC)
                || permissions.contains(AdminPermission.PAYMENT_VIEW)
                || permissions.contains(AdminPermission.USER_GDPR_DELETE)
                || permissions.contains(AdminPermission.ALERT_VIEW);
        if (anyQueue) {
            AdminQueueSnapshot q = cache.queues();
            putIf(counts, permissions, AdminPermission.REPORT_VIEW, "reports", q.openReports());
            putIf(counts, permissions, AdminPermission.SUPPORT_TICKET_VIEW, "support", q.supportFor(adminId));
            putIf(counts, permissions, AdminPermission.DISPUTE_VIEW, "incidents",
                    q.openDisputes() + q.pendingNoShows());
            putIf(counts, permissions, AdminPermission.USER_KYC, "kyc", q.kycInReview());
            putIf(counts, permissions, AdminPermission.PAYMENT_VIEW, "heldPayouts", q.heldPayouts());
            putIf(counts, permissions, AdminPermission.PAYMENT_VIEW, "walletRefunds", q.pendingWalletRefunds());
            putIf(counts, permissions, AdminPermission.USER_GDPR_DELETE, "gdpr", q.pendingGdpr());
            putIf(counts, permissions, AdminPermission.ALERT_VIEW, "alerts", q.unresolvedAlerts());
        }
        Instant now = clock.instant();
        Unread unread = unread(AdminNotificationType.visibleTo(permissions), lastSeen(adminId, now),
                now.minus(AdminNotificationCache.WINDOW));
        return new AdminNotificationCountersResponse(counts, unread.count(), unread.capped());
    }

    /**
     * Marque le fil comme vu jusqu'à {@code upTo} (maintenant par défaut, jamais dans le futur).
     * La requête refuse de faire reculer la date : un onglet resté ouvert ne ressuscite pas des
     * entrées déjà vues ailleurs.
     */
    public void markSeen(UUID adminId, Instant upTo) {
        Instant now = clock.instant();
        Instant seen = upTo == null || upTo.isAfter(now) ? now : upTo;
        admins.advanceNotificationsSeenAt(adminId, LocalDateTime.ofInstant(seen, ZoneOffset.UTC));
    }

    private Instant lastSeen(UUID adminId, Instant now) {
        return admins.findNotificationsSeenAt(adminId)
                .map(at -> at.toInstant(ZoneOffset.UTC))
                .orElse(now.minus(NEVER_SEEN_LOOKBACK));
    }

    /**
     * Compté sur les entrées en cache ({@link AdminNotificationCache#RECENT_DEPTH} par source) :
     * une source qui en a davantage dépasse de toute façon le plafond.
     */
    private Unread unread(List<AdminNotificationType> visible, Instant lastSeen, Instant from) {
        long count = 0;
        for (AdminNotificationType type : visible) {
            count += cache.recent(type).stream()
                    .filter(item -> item.createdAt().isAfter(lastSeen) && !item.createdAt().isBefore(from))
                    .count();
        }
        return new Unread((int) Math.min(count, UNREAD_CAP), count > UNREAD_CAP);
    }

    private static void putIf(Map<String, Long> counts, Set<AdminPermission> permissions,
                              AdminPermission required, String key, long value) {
        if (permissions.contains(required)) {
            counts.put(key, value);
        }
    }

    private record Unread(int count, boolean capped) {}
}
