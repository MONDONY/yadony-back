package com.yadony.api.admin.notifications;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.metrics.AdminQueueSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminNotificationService : fusion, permissions, non-lus, compteurs")
class AdminNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OTHER_ADMIN = UUID.randomUUID();

    @Mock AdminNotificationQueries queries;
    @Mock AdminNotificationCache cache;
    @Mock AdminUserRepository admins;

    private AdminNotificationService service;

    @BeforeEach
    void setUp() {
        service = new AdminNotificationService(queries, cache, admins);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        for (AdminNotificationType type : AdminNotificationType.values()) {
            when(cache.recent(type)).thenReturn(List.of());
        }
        when(admins.findNotificationsSeenAt(ADMIN)).thenReturn(Optional.empty());
        when(cache.queues()).thenReturn(snapshot());
    }

    private static AdminNotificationItem item(AdminNotificationType type, Instant at) {
        String id = type.name() + ":" + UUID.randomUUID();
        return new AdminNotificationItem(id, type, "t", "s", AdminNotificationSeverity.INFO, at, "/x");
    }

    private static Instant ago(Duration d) {
        return NOW.minus(d);
    }

    private void seenAt(Instant at) {
        when(admins.findNotificationsSeenAt(ADMIN))
                .thenReturn(Optional.of(LocalDateTime.ofInstant(at, ZoneOffset.UTC)));
    }

    private static Set<AdminPermission> all() {
        return EnumSet.allOf(AdminPermission.class);
    }

    // ------------------------------------------------------------------ feed

    @Test
    void feed_fusionneLesSources_trieDuPlusRecentAuPlusAncien_etTronqueALaLimite() {
        AdminNotificationItem r1 = item(AdminNotificationType.REPORT_CREATED, ago(Duration.ofMinutes(5)));
        AdminNotificationItem r2 = item(AdminNotificationType.REPORT_CREATED, ago(Duration.ofHours(3)));
        AdminNotificationItem a1 = item(AdminNotificationType.ADMIN_ALERT, ago(Duration.ofMinutes(1)));
        AdminNotificationItem p1 = item(AdminNotificationType.PAYOUT_HELD, ago(Duration.ofHours(1)));
        when(cache.recent(AdminNotificationType.REPORT_CREATED)).thenReturn(List.of(r1, r2));
        when(cache.recent(AdminNotificationType.ADMIN_ALERT)).thenReturn(List.of(a1));
        when(cache.recent(AdminNotificationType.PAYOUT_HELD)).thenReturn(List.of(p1));

        AdminNotificationFeedResponse feed = service.feed(ADMIN, all(), null, 3);

        assertThat(feed.items()).containsExactly(a1, r1, p1);
    }

    @Test
    void feed_filtreParPermission_leSupportNeVoitPasLesVersementsRetenus() {
        AdminNotificationItem ticket = item(AdminNotificationType.SUPPORT_TICKET_CREATED, ago(Duration.ofMinutes(2)));
        AdminNotificationItem held = item(AdminNotificationType.PAYOUT_HELD, ago(Duration.ofMinutes(1)));
        when(cache.recent(AdminNotificationType.SUPPORT_TICKET_CREATED)).thenReturn(List.of(ticket));
        when(cache.recent(AdminNotificationType.PAYOUT_HELD)).thenReturn(List.of(held));

        AdminNotificationFeedResponse feed = service.feed(ADMIN,
                EnumSet.of(AdminPermission.SUPPORT_TICKET_VIEW), null, 30);

        assertThat(feed.items()).containsExactly(ticket);
        assertThat(feed.unreadCount()).isEqualTo(1);
        verify(cache, never()).recent(AdminNotificationType.PAYOUT_HELD);
    }

    @Test
    void feed_sansAucunePermission_estVide() {
        AdminNotificationFeedResponse feed = service.feed(ADMIN, EnumSet.noneOf(AdminPermission.class), null, 30);

        assertThat(feed.items()).isEmpty();
        assertThat(feed.unreadCount()).isZero();
    }

    @Test
    void feed_ecarteCeQuiSortDeLaFenetreDeTrenteJours() {
        AdminNotificationItem recent = item(AdminNotificationType.DISPUTE_OPENED, ago(Duration.ofDays(29)));
        AdminNotificationItem old = item(AdminNotificationType.DISPUTE_OPENED, ago(Duration.ofDays(31)));
        when(cache.recent(AdminNotificationType.DISPUTE_OPENED)).thenReturn(List.of(recent, old));

        assertThat(service.feed(ADMIN, all(), null, 30).items()).containsExactly(recent);
    }

    @Test
    void feed_avecCurseur_interrogeLesSourcesAvantLeCurseur_dansLaFenetre() {
        Instant before = ago(Duration.ofDays(2));
        // Le cache ne sert qu'au non-lu : ses entrées récentes ne s'invitent pas dans une page paginée.
        AdminNotificationItem newer = item(AdminNotificationType.GDPR_REQUESTED, ago(Duration.ofHours(1)));
        when(cache.recent(AdminNotificationType.GDPR_REQUESTED)).thenReturn(List.of(newer));
        AdminNotificationItem older = item(AdminNotificationType.GDPR_REQUESTED, ago(Duration.ofDays(3)));
        when(queries.find(eq(AdminNotificationType.GDPR_REQUESTED), any(), eq(before), anyInt()))
                .thenReturn(List.of(older));
        when(queries.find(eq(AdminNotificationType.KYC_IN_REVIEW), any(), eq(before), anyInt()))
                .thenReturn(List.of());

        AdminNotificationFeedResponse feed = service.feed(ADMIN,
                EnumSet.of(AdminPermission.USER_GDPR_DELETE, AdminPermission.USER_KYC), before, 10);

        assertThat(feed.items()).containsExactly(older);
        verify(queries).find(AdminNotificationType.GDPR_REQUESTED, NOW.minus(Duration.ofDays(30)), before, 10);
        assertThat(feed.unreadCount()).isEqualTo(1);
    }

    @Test
    void feed_curseurAuDelaDeLaFenetre_renvoieUnePageVideSansRequete() {
        AdminNotificationFeedResponse feed = service.feed(ADMIN, all(), ago(Duration.ofDays(40)), 10);

        assertThat(feed.items()).isEmpty();
        verify(queries, never()).find(any(), any(), any(), anyInt());
    }

    @Test
    void feed_limiteBorneeEntreUnEtCent() {
        List<AdminNotificationItem> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(item(AdminNotificationType.REPORT_CREATED, ago(Duration.ofMinutes(i + 1))));
        }
        when(cache.recent(AdminNotificationType.REPORT_CREATED)).thenReturn(many);

        assertThat(service.feed(ADMIN, all(), null, 500).items()).hasSize(100);
        assertThat(service.feed(ADMIN, all(), null, 0).items()).hasSize(1);
    }

    // --------------------------------------------------------------- non-lus

    @Test
    void nonLus_sansConsultation_lesSeptDerniersJoursComptent() {
        when(cache.recent(AdminNotificationType.REPORT_CREATED)).thenReturn(List.of(
                item(AdminNotificationType.REPORT_CREATED, ago(Duration.ofDays(1))),
                item(AdminNotificationType.REPORT_CREATED, ago(Duration.ofDays(6))),
                item(AdminNotificationType.REPORT_CREATED, ago(Duration.ofDays(8)))));

        AdminNotificationFeedResponse feed = service.feed(ADMIN, all(), null, 30);

        assertThat(feed.unreadCount()).isEqualTo(2);
        assertThat(feed.unreadCapped()).isFalse();
        assertThat(feed.lastSeenAt()).isEqualTo(NOW.minus(Duration.ofDays(7)));
        assertThat(feed.items()).hasSize(3);
    }

    @Test
    void nonLus_apresConsultation_seulsLesElementsPosterieursComptent() {
        seenAt(ago(Duration.ofHours(2)));
        when(cache.recent(AdminNotificationType.ADMIN_ALERT)).thenReturn(List.of(
                item(AdminNotificationType.ADMIN_ALERT, ago(Duration.ofHours(1))),
                item(AdminNotificationType.ADMIN_ALERT, ago(Duration.ofHours(3)))));

        AdminNotificationFeedResponse feed = service.feed(ADMIN, all(), null, 30);

        assertThat(feed.unreadCount()).isEqualTo(1);
        assertThat(feed.lastSeenAt()).isEqualTo(ago(Duration.ofHours(2)));
    }

    @Test
    void nonLus_plafonnesAQuatreVingtDixNeuf() {
        List<AdminNotificationItem> reports = new ArrayList<>();
        List<AdminNotificationItem> alerts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            reports.add(item(AdminNotificationType.REPORT_CREATED, ago(Duration.ofMinutes(i + 1))));
            alerts.add(item(AdminNotificationType.ADMIN_ALERT, ago(Duration.ofMinutes(i + 1))));
        }
        when(cache.recent(AdminNotificationType.REPORT_CREATED)).thenReturn(reports);
        when(cache.recent(AdminNotificationType.ADMIN_ALERT)).thenReturn(alerts);

        AdminNotificationFeedResponse feed = service.feed(ADMIN, all(), null, 30);

        assertThat(feed.unreadCount()).isEqualTo(99);
        assertThat(feed.unreadCapped()).isTrue();

        AdminNotificationCountersResponse counters = service.counters(ADMIN, all());
        assertThat(counters.unreadCount()).isEqualTo(99);
        assertThat(counters.unreadCapped()).isTrue();
    }

    @Test
    void nonLus_exactementQuatreVingtDixNeuf_nEstPasPlafonne() {
        List<AdminNotificationItem> reports = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            reports.add(item(AdminNotificationType.REPORT_CREATED, ago(Duration.ofMinutes(i + 1))));
        }
        when(cache.recent(AdminNotificationType.REPORT_CREATED)).thenReturn(reports);

        AdminNotificationFeedResponse feed = service.feed(ADMIN, all(), null, 30);

        assertThat(feed.unreadCount()).isEqualTo(99);
        assertThat(feed.unreadCapped()).isFalse();
    }

    // -------------------------------------------------------------- compteurs

    private static AdminQueueSnapshot snapshot() {
        return new AdminQueueSnapshot(4, 2, Map.of(ADMIN, 3L, OTHER_ADMIN, 7L), 5, 1, 6, 2, 3, 8, 9);
    }

    @Test
    void compteurs_toutesPermissions_toutesLesCles() {
        when(cache.queues()).thenReturn(snapshot());

        AdminNotificationCountersResponse counters = service.counters(ADMIN, all());

        assertThat(counters.counts()).containsExactly(
                Map.entry("reports", 4L),
                Map.entry("support", 5L),
                Map.entry("incidents", 6L),
                Map.entry("kyc", 6L),
                Map.entry("heldPayouts", 2L),
                Map.entry("walletRefunds", 3L),
                Map.entry("gdpr", 8L),
                Map.entry("alerts", 9L));
    }

    @Test
    void compteurs_supportSeul_lesAutresClesSontAbsentes() {
        when(cache.queues()).thenReturn(snapshot());

        AdminNotificationCountersResponse counters = service.counters(OTHER_ADMIN,
                EnumSet.of(AdminPermission.SUPPORT_TICKET_VIEW, AdminPermission.REPORT_VIEW));

        assertThat(counters.counts()).containsOnlyKeys("support", "reports");
        // Tickets non assignés (2) + assignés à cet administrateur (7).
        assertThat(counters.counts()).containsEntry("support", 9L);
    }

    @Test
    void compteurs_sansPermission_neTouchentPasLesFiles() {
        AdminNotificationCountersResponse counters = service.counters(ADMIN, EnumSet.noneOf(AdminPermission.class));

        assertThat(counters.counts()).isEmpty();
        assertThat(counters.unreadCount()).isZero();
        verify(cache, never()).queues();
    }

    @Test
    void compteurs_portentLeNonLuDuFil() {
        when(cache.queues()).thenReturn(snapshot());
        when(cache.recent(AdminNotificationType.KYC_IN_REVIEW)).thenReturn(List.of(
                item(AdminNotificationType.KYC_IN_REVIEW, ago(Duration.ofHours(1)))));

        assertThat(service.counters(ADMIN, EnumSet.of(AdminPermission.USER_KYC)).unreadCount()).isEqualTo(1);
        assertThat(service.counters(ADMIN, EnumSet.of(AdminPermission.ALERT_VIEW)).unreadCount()).isZero();
    }

    // -------------------------------------------------------------- mark-seen

    @Test
    void markSeen_sansDate_poseMaintenant() {
        service.markSeen(ADMIN, null);

        verify(admins).advanceNotificationsSeenAt(ADMIN, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void markSeen_dateFuture_estRameneeAMaintenant() {
        service.markSeen(ADMIN, NOW.plus(Duration.ofDays(3)));

        verify(admins).advanceNotificationsSeenAt(ADMIN, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    void markSeen_datePassee_estTransmise_leReculEstRefuseParLaRequete() {
        Instant upTo = ago(Duration.ofHours(1));

        service.markSeen(ADMIN, upTo);

        verify(admins).advanceNotificationsSeenAt(ADMIN, LocalDateTime.ofInstant(upTo, ZoneOffset.UTC));
    }
}
