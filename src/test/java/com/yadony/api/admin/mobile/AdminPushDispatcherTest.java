package com.yadony.api.admin.mobile;

import com.yadony.api.admin.account.AdminPermission;
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
import static org.mockito.Mockito.when;

class AdminPushDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");

    private AdminNotificationService notifications;
    private AdminUserRepository admins;
    private AdminDeviceRepository devices;
    private final List<Sent> sent = new ArrayList<>();
    private int failAfter = -1;
    private final List<String> invalidTokens = new ArrayList<>();
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
        when(devices.findActive()).thenAnswer(i -> List.copyOf(stored));
        when(devices.save(any(AdminDeviceEntity.class))).thenAnswer(i -> i.getArgument(0));
        AdminPushSender sender = new AdminPushSender() {
            @Override
            public SendResult send(String token, String title, String body, Map<String, String> data) {
                if (invalidTokens.contains(token)) return SendResult.INVALID_TOKEN;
                if (failAfter >= 0 && sent.size() >= failAfter) return SendResult.ERROR;
                sent.add(new Sent(token, title, body, data));
                return SendResult.OK;
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
    void dispatcher_sendsOnlyTypesTheAdminMayRead() {
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
        // les permissions de l'admin sont bien transmises au fil
        org.mockito.ArgumentCaptor<Set<AdminPermission>> perms = org.mockito.ArgumentCaptor.forClass(Set.class);
        org.mockito.Mockito.verify(notifications).feed(eq(id), perms.capture(), any(), anyInt());
        assertThat(perms.getValue()).containsAll(AdminRole.SUPER_ADMIN.permissions());
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
}
