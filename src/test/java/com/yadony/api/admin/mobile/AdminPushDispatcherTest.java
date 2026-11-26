package com.yadony.api.admin.mobile;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPermissions;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminStatus;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.notifications.AdminNotificationFeedResponse;
import com.yadony.api.admin.notifications.AdminNotificationItem;
import com.yadony.api.admin.notifications.AdminNotificationService;
import com.yadony.api.admin.notifications.AdminNotificationSeverity;
import com.yadony.api.admin.notifications.AdminNotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPushDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");

    private AdminNotificationService notifications;
    private AdminUserRepository admins;
    private AdminDeviceRepository devices;
    private final List<Sent> sent = new ArrayList<>();
    private int failAfter = -1;
    private final List<String> invalidTokens = new ArrayList<>();
    /** Jetons en échec persistant, avec le résultat rendu à chaque envoi. */
    private final Map<String, AdminPushSender.SendResult> failing = new java.util.HashMap<>();
    private int attempts;
    private AdminPushDispatcher dispatcher;
    private final java.time.Clock clock = mock(java.time.Clock.class);
    private void at(Instant now) { when(clock.instant()).thenReturn(now); }
    private final List<AdminDeviceEntity> stored = new ArrayList<>();

    record Sent(String token, String title, String body, Map<String, String> data) {}

    @BeforeEach
    void setUp() {
        notifications = mock(AdminNotificationService.class);
        admins = mock(AdminUserRepository.class);
        devices = mock(AdminDeviceRepository.class);
        at(T0.plusSeconds(10));
        when(devices.findActive()).thenAnswer(i -> stored.stream().filter(d -> d.getDeletedAt() == null).toList());
        // Faux UPDATE ciblés, mêmes gardes que les requêtes JPQL (couvertes par AdminDeviceControllerIT).
        when(devices.advanceLastPushedAt(any(), any(), any())).thenAnswer(i -> {
            UUID deviceId = i.getArgument(0);
            UUID adminId = i.getArgument(1);
            Instant at = i.getArgument(2);
            for (AdminDeviceEntity d : stored) {
                if (d.getId().equals(deviceId) && d.getAdminId().equals(adminId) && d.getDeletedAt() == null
                        && d.getLastPushedAt().isBefore(at)) {
                    d.setLastPushedAt(at);
                    return 1;
                }
            }
            return 0;
        });
        when(devices.softDeleteActive(any(), any())).thenAnswer(i -> {
            UUID deviceId = i.getArgument(0);
            for (AdminDeviceEntity d : stored) {
                if (d.getId().equals(deviceId) && d.getDeletedAt() == null) {
                    d.setDeletedAt(i.getArgument(1));
                    return 1;
                }
            }
            return 0;
        });
        AdminPushSender sender = new AdminPushSender() {
            @Override
            public SendOutcome send(String token, String title, String body, Map<String, String> data) {
                attempts++;
                if (invalidTokens.contains(token)) return new SendOutcome(SendResult.INVALID_TOKEN, "unregistered");
                if (failing.containsKey(token)) return new SendOutcome(failing.get(token), "refus FCM");
                if (failAfter >= 0 && sent.size() >= failAfter) return new SendOutcome(SendResult.ERROR, "refus FCM");
                sent.add(new Sent(token, title, body, data));
                return new SendOutcome(SendResult.OK, null);
            }
        };
        dispatcher = new AdminPushDispatcher(devices, admins, notifications, sender);
        dispatcher.setClock(clock);
    }

    private AdminUserEntity admin(UUID id, AdminStatus status) {
        AdminUserEntity a = new AdminUserEntity("uid-" + id, id + "@x.fr", AdminRole.SUPER_ADMIN);
        org.springframework.test.util.ReflectionTestUtils.setField(a, "id", id);
        a.setStatus(status);
        when(admins.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    private AdminDeviceEntity device(UUID adminId, String token, Instant cursor) {
        AdminDeviceEntity d = new AdminDeviceEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        d.setAdminId(adminId);
        d.setFcmToken(token);
        d.setLastPushedAt(cursor);
        stored.add(d);
        return d;
    }

    /** Lien comme {@code AdminNotificationItems} : le ticket support n'est porté que par le lien. */
    private static AdminNotificationItem item(AdminNotificationType type, UUID entityId, Instant at) {
        boolean support = type == AdminNotificationType.SUPPORT_TICKET_CREATED
                || type == AdminNotificationType.SUPPORT_MESSAGE_RECEIVED;
        return new AdminNotificationItem(type.name() + ":" + entityId, type, "Titre secret Jean", "Résumé 42 EUR",
                AdminNotificationSeverity.INFO, at, support ? "/support?ticket=" + entityId : "/x");
    }

    private static AdminNotificationItem itemWithLink(AdminNotificationType type, UUID entityId, String link) {
        return new AdminNotificationItem(type.name() + ":" + entityId, type, "t", "s",
                AdminNotificationSeverity.INFO, T0, link);
    }

    private void feedReturns(UUID adminId, AdminNotificationItem... items) {
        when(notifications.feed(eq(adminId), any(), any(), anyInt())).thenReturn(
                new AdminNotificationFeedResponse(List.of(items), 0, false, T0, List.of()));
    }

    @Test
    void dispatcher_sendsGenericCopy_andRoute() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        device(id, "tok1", T0);
        UUID alertId = UUID.randomUUID();
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, alertId, T0.plusSeconds(5)));

        int n = dispatcher.runOnce();

        assertThat(n).isEqualTo(1);
        assertThat(sent).hasSize(1);
        Sent s = sent.get(0);
        assertThat(s.title()).isEqualTo("Nouvelle alerte");
        assertThat(s.body()).doesNotContain("Jean").doesNotContain("42");
        assertThat(s.data()).containsEntry("type", "ADMIN_ALERT").containsEntry("route", "/m/alertes/" + alertId);
        assertThat(s.data()).hasSize(2);
    }

    // ── Permissions (F7) ─────────────────────────────────────────────────────

    private AdminUserEntity adminWith(UUID id, AdminRole role, Map<String, Boolean> overrides) {
        AdminUserEntity a = new AdminUserEntity("uid-" + id, id + "@x.fr", role);
        org.springframework.test.util.ReflectionTestUtils.setField(a, "id", id);
        a.setStatus(AdminStatus.ACTIVE);
        a.setPermissionOverrides(new java.util.HashMap<>(overrides));
        when(admins.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    /** Le fil filtre par permission (contrat de AdminNotificationService.feed) : on le reproduit. */
    private void feedFiltersByPermission(UUID adminId, AdminNotificationItem... items) {
        when(notifications.feed(eq(adminId), any(), any(), anyInt())).thenAnswer(inv -> {
            Set<AdminPermission> permissions = inv.getArgument(1);
            List<AdminNotificationItem> visible = java.util.Arrays.stream(items)
                    .filter(i -> permissions.contains(i.type().permission())).toList();
            return new AdminNotificationFeedResponse(visible, 0, false, T0, List.of());
        });
    }

    @SuppressWarnings("unchecked")
    private Set<AdminPermission> permissionsPassedToFeed(UUID adminId) {
        org.mockito.ArgumentCaptor<Set<AdminPermission>> perms = org.mockito.ArgumentCaptor.forClass(Set.class);
        verify(notifications).feed(eq(adminId), perms.capture(), any(), anyInt());
        return perms.getValue();
    }

    @Test
    void dispatcher_readsTheFeedWithTheAdminsEffectivePermissions_notAllOfThem() {
        UUID id = UUID.randomUUID();
        Map<String, Boolean> overrides = Map.of("PAYMENT_VIEW", false, "USER_GDPR_DELETE", true);
        adminWith(id, AdminRole.SUPPORT, overrides);
        device(id, "tok1", T0);
        feedReturns(id, item(AdminNotificationType.SUPPORT_TICKET_CREATED, UUID.randomUUID(), T0.plusSeconds(5)));

        dispatcher.runOnce();

        Set<AdminPermission> passed = permissionsPassedToFeed(id);
        assertThat(passed).isEqualTo(AdminPermissions.effective(AdminRole.SUPPORT, overrides));
        assertThat(passed).contains(AdminPermission.SUPPORT_TICKET_VIEW, AdminPermission.USER_GDPR_DELETE)
                .doesNotContain(AdminPermission.PAYMENT_VIEW, AdminPermission.ADMIN_MANAGE,
                        AdminPermission.USER_SUSPEND);
        assertThat(passed).isNotEqualTo(AdminRole.SUPER_ADMIN.permissions());
    }

    @Test
    void dispatcher_neverPushesTypesWhosePermissionWasRemoved() {
        UUID id = UUID.randomUUID();
        adminWith(id, AdminRole.SUPPORT, Map.of("PAYMENT_VIEW", false));
        device(id, "tok1", T0);
        UUID ticket = UUID.randomUUID();
        feedFiltersByPermission(id,
                item(AdminNotificationType.PAYOUT_HELD, UUID.randomUUID(), T0.plusSeconds(1)),
                item(AdminNotificationType.WALLET_REFUND_REQUESTED, UUID.randomUUID(), T0.plusSeconds(2)),
                item(AdminNotificationType.GDPR_REQUESTED, UUID.randomUUID(), T0.plusSeconds(3)),
                item(AdminNotificationType.SUPPORT_TICKET_CREATED, ticket, T0.plusSeconds(4)));

        assertThat(dispatcher.runOnce()).isEqualTo(1);

        assertThat(sent).extracting(s -> s.data().get("type")).containsExactly("SUPPORT_TICKET_CREATED");
        assertThat(sent).extracting(s -> s.data().get("route")).containsExactly("/m/support/" + ticket);
    }

    @Test
    void dispatcher_routesSupportAndOthers() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        device(id, "tok1", T0);
        UUID ticket = UUID.randomUUID();
        UUID dispute = UUID.randomUUID();
        feedReturns(id,
                item(AdminNotificationType.SUPPORT_TICKET_CREATED, ticket, T0.plusSeconds(2)),
                item(AdminNotificationType.DISPUTE_OPENED, dispute, T0.plusSeconds(1)));

        dispatcher.runOnce();

        assertThat(sent).extracting(s -> s.data().get("route"))
                .containsExactlyInAnyOrder("/m/support/" + ticket, "/m/alertes?item=DISPUTE_OPENED:" + dispute);
    }

    @Test
    void routeFor_adminAlert_opensTheAlert() {
        UUID alert = UUID.randomUUID();
        assertThat(AdminPushDispatcher.routeFor(itemWithLink(AdminNotificationType.ADMIN_ALERT, alert, "/alertes")))
                .isEqualTo("/m/alertes/" + alert);
    }

    @Test
    void routeFor_supportTicket_opensTheTicket() {
        UUID ticket = UUID.randomUUID();
        assertThat(AdminPushDispatcher.routeFor(itemWithLink(AdminNotificationType.SUPPORT_TICKET_CREATED, ticket,
                "/support?ticket=" + ticket))).isEqualTo("/m/support/" + ticket);
    }

    /** L'id d'un message support est celui du message ; le ticket n'est que dans le lien (régression F2). */
    @Test
    void routeFor_supportMessage_opensTheTicket_notTheMessageId() {
        UUID message = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        String route = AdminPushDispatcher.routeFor(itemWithLink(AdminNotificationType.SUPPORT_MESSAGE_RECEIVED,
                message, "/support?ticket=" + ticket));
        assertThat(route).isEqualTo("/m/support/" + ticket).doesNotContain(message.toString());
    }

    @Test
    void routeFor_supportWithoutTicketInLink_opensTheSupportList() {
        UUID message = UUID.randomUUID();
        assertThat(AdminPushDispatcher.routeFor(itemWithLink(AdminNotificationType.SUPPORT_MESSAGE_RECEIVED,
                message, "/support"))).isEqualTo("/m/support");
        assertThat(AdminPushDispatcher.routeFor(itemWithLink(AdminNotificationType.SUPPORT_MESSAGE_RECEIVED,
                message, "/support?ticket=pas-un-uuid"))).isEqualTo("/m/support");
        assertThat(AdminPushDispatcher.routeFor(itemWithLink(AdminNotificationType.SUPPORT_TICKET_CREATED,
                message, null))).isEqualTo("/m/support");
    }

    @Test
    void routeFor_otherTypes_openTheAlertListOnTheItem() {
        java.util.EnumSet<AdminNotificationType> others = java.util.EnumSet.complementOf(java.util.EnumSet.of(
                AdminNotificationType.ADMIN_ALERT, AdminNotificationType.SUPPORT_TICKET_CREATED,
                AdminNotificationType.SUPPORT_MESSAGE_RECEIVED));
        assertThat(others).hasSize(7);
        for (AdminNotificationType type : others) {
            UUID entity = UUID.randomUUID();
            assertThat(AdminPushDispatcher.routeFor(itemWithLink(type, entity, "/x")))
                    .as(type.name())
                    .isEqualTo("/m/alertes?item=" + type.name() + ":" + entity);
        }
    }

    @Test
    void dispatcher_doesNotResendAfterCursorAdvanced() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        AdminDeviceEntity d = device(id, "tok1", T0);
        Instant at = T0.plusSeconds(5);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), at));

        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(d.getLastPushedAt()).isAfterOrEqualTo(T0);
        assertThat(dispatcher.runOnce()).isZero();
        assertThat(sent).hasSize(1);
    }

    @Test
    void dispatcher_removesDevice_onInvalidToken_andKeepsOthers() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        AdminDeviceEntity bad = device(id, "bad", T0);
        AdminDeviceEntity good = device(id, "good", T0);
        invalidTokens.add("bad");
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));

        int n = dispatcher.runOnce();

        assertThat(n).isEqualTo(1);
        assertThat(bad.getDeletedAt()).isNotNull();
        assertThat(good.getDeletedAt()).isNull();
        assertThat(sent).extracting(Sent::token).containsExactly("good");
    }

    @Test
    void dispatcher_skipsDisabledAdmin() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.DISABLED);
        device(id, "tok1", T0);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));

        assertThat(dispatcher.runOnce()).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void dispatcher_doesNotLoseItemFromStaleCacheType() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        device(id, "tok1", T0);
        UUID bId = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        AdminNotificationItem b = item(AdminNotificationType.ADMIN_ALERT, bId, T0.plusSeconds(30));
        AdminNotificationItem a = item(AdminNotificationType.SUPPORT_TICKET_CREATED, aId, T0.plusSeconds(20));
        at(T0.plusSeconds(40));
        feedReturns(id, b);
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        at(T0.plusSeconds(50));
        feedReturns(id, b, a);
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(sent).extracting(s -> s.data().get("route"))
                .containsExactly("/m/alertes/" + bId, "/m/support/" + aId);
    }

    @Test
    void dispatcher_dedupesWithinLookback() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        device(id, "tok1", T0);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(30)));
        at(T0.plusSeconds(40));
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        at(T0.plusSeconds(60));
        assertThat(dispatcher.runOnce()).isZero();
        assertThat(sent).hasSize(1);
    }

    @Test
    void dispatcher_partialFailure_doesNotResendDeliveredItems() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        AdminDeviceEntity d = device(id, "tok1", T0);
        AdminNotificationItem first = item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(1));
        AdminNotificationItem second = item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(2));
        feedReturns(id, second, first);
        failAfter = 1;
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(d.getLastPushedAt()).isEqualTo(first.createdAt());
        failAfter = -1;
        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).data().get("route")).isEqualTo("/m/alertes/" + second.id().substring(second.id().indexOf(':') + 1));
    }

    // ── Plancher du curseur (F3) et écritures ciblées (F9) ───────────────────

    private static AdminNotificationItem[] oldAlerts(int count, Instant from) {
        AdminNotificationItem[] items = new AdminNotificationItem[count];
        for (int i = 0; i < count; i++) {
            items[i] = item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), from.plus(Duration.ofHours(i)));
        }
        return items;
    }

    /** Appareil repris, admin réactivé ou sorti d'une série d'erreurs : curseur vieux de 3 jours. */
    @Test
    void dispatcher_staleCursor_neverPushesItemsOlderThanMaxAge() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        Instant now = T0.plus(Duration.ofDays(3));
        AdminDeviceEntity d = device(id, "tok1", T0);
        feedReturns(id, oldAlerts(12, T0.plusSeconds(60)));
        at(now);

        assertThat(dispatcher.runOnce()).isZero();
        assertThat(dispatcher.runOnce()).isZero();

        assertThat(sent).isEmpty();
        assertThat(d.getLastPushedAt()).isEqualTo(T0);
    }

    @Test
    void dispatcher_staleCursor_stillPushesItemsWithinMaxAge() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        Instant now = T0.plus(Duration.ofDays(3));
        device(id, "tok1", T0);
        AdminNotificationItem[] old = oldAlerts(12, T0.plusSeconds(60));
        UUID recentId = UUID.randomUUID();
        AdminNotificationItem recent = item(AdminNotificationType.ADMIN_ALERT, recentId,
                now.minus(AdminPushDispatcher.MAX_AGE).plusSeconds(60));
        AdminNotificationItem[] all = java.util.Arrays.copyOf(old, old.length + 1);
        all[old.length] = recent;
        feedReturns(id, all);
        at(now);

        assertThat(dispatcher.runOnce()).isEqualTo(1);
        assertThat(sent).extracting(s -> s.data().get("route")).containsExactly("/m/alertes/" + recentId);
    }

    @Test
    void dispatcher_idleDevice_isNeverWritten() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        device(id, "tok1", T0);
        feedReturns(id);

        for (int pass = 0; pass < 5; pass++) {
            at(T0.plusSeconds(10 + 30L * pass));
            assertThat(dispatcher.runOnce()).isZero();
        }

        verify(devices, never()).advanceLastPushedAt(any(), any(), any());
        verify(devices, never()).softDeleteActive(any(), any());
        verify(devices, never()).save(any());
    }

    @Test
    void dispatcher_writesCursorWithTargetedUpdate_neverWithSave() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        AdminDeviceEntity d = device(id, "tok1", T0);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));
        at(T0.plusSeconds(120));

        assertThat(dispatcher.runOnce()).isEqualTo(1);

        verify(devices).advanceLastPushedAt(d.getId(), id, T0.plusSeconds(120).minus(AdminPushDispatcher.LOOKBACK));
        verify(devices, never()).save(any());
    }

    /** Sans écriture des appareils au repos, l'anti-doublon doit couvrir toute la fenêtre du plancher. */
    @Test
    void dispatcher_neverResendsAnItem_whileItStaysWithinMaxAge() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        device(id, "tok1", T0);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));
        at(T0.plusSeconds(10));
        assertThat(dispatcher.runOnce()).isEqualTo(1);

        // 25 minutes de passages : au-delà de MAX_AGE et de l'ancienne durée de l'anti-doublon (10 min).
        for (int pass = 1; pass <= 50; pass++) {
            at(T0.plusSeconds(10 + 30L * pass));
            assertThat(dispatcher.runOnce()).as("passage %d", pass).isZero();
        }
        assertThat(sent).hasSize(1);
    }

    @Test
    void dispatcher_invalidToken_softDeletesWithTargetedUpdate() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        AdminDeviceEntity bad = device(id, "bad", T0);
        invalidTokens.add("bad");
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));

        dispatcher.runOnce();

        verify(devices).softDeleteActive(eq(bad.getId()), any());
        verify(devices, never()).advanceLastPushedAt(eq(bad.getId()), any(), any());
        verify(devices, never()).save(any());
    }

    // ── Erreurs FCM persistantes (F4) ────────────────────────────────────────

    /** Un passage toutes les 30 s à partir de T0 + 10 s ; rend le nombre de push envoyés. */
    private int passes(int from, int count) {
        int total = 0;
        for (int pass = from; pass < from + count; pass++) {
            at(T0.plusSeconds(10 + 30L * pass));
            total += dispatcher.runOnce();
        }
        return total;
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminPushDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(AdminPushDispatcher.class)).detachAppender(appender);
    }

    private static long count(ListAppender<ILoggingEvent> appender, Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).count();
    }

    @Test
    void dispatcher_removesDevice_after20ConsecutiveErrors() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        AdminDeviceEntity d = device(id, "tok1", T0);
        failing.put("tok1", AdminPushSender.SendResult.ERROR);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));

        passes(0, AdminPushDispatcher.MAX_CONSECUTIVE_ERRORS - 1);
        assertThat(d.getDeletedAt()).isNull();
        verify(devices, never()).softDeleteActive(any(), any());

        passes(AdminPushDispatcher.MAX_CONSECUTIVE_ERRORS - 1, 1);
        assertThat(d.getDeletedAt()).isNotNull();
        verify(devices).softDeleteActive(eq(d.getId()), any());

        int before = attempts;
        passes(AdminPushDispatcher.MAX_CONSECUTIVE_ERRORS, 3);
        assertThat(attempts).as("appareil retiré : plus aucun envoi").isEqualTo(before);
    }

    @Test
    void dispatcher_successResetsTheFailureStreak() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        AdminDeviceEntity d = device(id, "tok1", T0);
        failing.put("tok1", AdminPushSender.SendResult.ERROR);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));
        passes(0, AdminPushDispatcher.MAX_CONSECUTIVE_ERRORS - 1);

        failing.remove("tok1");
        assertThat(passes(19, 1)).isEqualTo(1);

        failing.put("tok1", AdminPushSender.SendResult.ERROR);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(20 * 30)));
        passes(20, AdminPushDispatcher.MAX_CONSECUTIVE_ERRORS - 1);

        assertThat(d.getDeletedAt()).isNull();
        verify(devices, never()).softDeleteActive(any(), any());
    }

    /** FCM indisponible ou mal configuré : jamais la faute de l'appareil, qui reste abonné. */
    @Test
    void dispatcher_serviceErrors_neverRemoveTheDevice() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        AdminDeviceEntity d = device(id, "tok1", T0);
        failing.put("tok1", AdminPushSender.SendResult.SERVICE_ERROR);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));

        passes(0, 25);

        assertThat(attempts).isEqualTo(25);
        assertThat(d.getDeletedAt()).isNull();
        verify(devices, never()).softDeleteActive(any(), any());
    }

    /** Sentry ne retient que les ERROR : la première d'une série, les suivantes en WARN. */
    @Test
    void dispatcher_logsOnlyTheFirstFailureOfAStreakAtError() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        device(id, "tok1", T0);
        failing.put("tok1", AdminPushSender.SendResult.ERROR);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            passes(0, 5);
            assertThat(count(logs, Level.ERROR)).isEqualTo(1);
            assertThat(count(logs, Level.WARN)).isEqualTo(4);

            failing.remove("tok1");
            passes(5, 1);
            failing.put("tok1", AdminPushSender.SendResult.ERROR);
            feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(6 * 30)));
            passes(6, 3);
            assertThat(count(logs, Level.ERROR)).as("nouvelle série après un succès").isEqualTo(2);
        } finally {
            release(logs);
        }
    }

    @Test
    void dispatcher_logsDeviceRemovalOnce() {
        UUID id = UUID.randomUUID();
        admin(id, AdminStatus.ACTIVE);
        device(id, "tok1", T0);
        failing.put("tok1", AdminPushSender.SendResult.ERROR);
        feedReturns(id, item(AdminNotificationType.ADMIN_ALERT, UUID.randomUUID(), T0.plusSeconds(5)));
        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            passes(0, AdminPushDispatcher.MAX_CONSECUTIVE_ERRORS + 3);
            assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("retiré après"))
                    .hasSize(1)
                    .allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
            assertThat(count(logs, Level.ERROR)).isEqualTo(1);
        } finally {
            release(logs);
        }
    }
}
