package com.yadony.api.admin.notifications;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.metrics.AdminQueueCounter;
import com.yadony.api.admin.metrics.AdminQueueSnapshot;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.kyc.KycVerificationEntity;
import com.yadony.api.kyc.KycVerificationStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.wallet.WalletRefundChannel;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportStatus;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.support.SupportMessageAuthorType;
import com.yadony.api.support.SupportMessageEntity;
import com.yadony.api.support.SupportPriority;
import com.yadony.api.support.SupportTicketEntity;
import com.yadony.api.support.SupportTicketStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaque source du fil contre une vraie base (H2 en mode PostgreSQL). La base de test est
 * partagée entre classes : les assertions portent sur les lignes créées ici, jamais sur un total.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AdminNotificationQueries : une requête par source")
class AdminNotificationQueriesIT {

    @Autowired AdminNotificationQueries queries;
    @Autowired AdminQueueCounter counter;
    @Autowired AdminUserRepository adminUsers;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant windowStart() {
        return now().minus(Duration.ofDays(30));
    }

    private <T> T persist(T entity) {
        return tx.execute(s -> {
            em.persist(entity);
            return entity;
        });
    }

    /**
     * Recule la date stockée de {@code age}, relativement à la valeur écrite par Hibernate : une
     * date absolue écrite en JDBC ne serait pas comparable (conversion de fuseau de
     * {@code hibernate.jdbc.time_zone} selon le fuseau de la JVM).
     */
    private void backdate(String table, String column, UUID id, Duration age) {
        LocalDateTime stored = jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id = ?", LocalDateTime.class, id);
        jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?", stored.minus(age), id);
    }

    private List<String> ids(AdminNotificationType type) {
        return queries.find(type, windowStart(), null, 100).stream().map(AdminNotificationItem::id).toList();
    }

    private UserEntity user(String first, String last) {
        UserEntity u = new UserEntity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        u.setFirebaseUid("uid-notif-" + suffix);
        u.setUsername("notif-" + suffix);
        u.setFirstName(first);
        u.setLastName(last);
        u.setStatus(UserStatus.ACTIVE);
        u.setRoles(Set.of(Role.SENDER));
        return persist(u);
    }

    // ---------------------------------------------------------------- reports

    @Test
    void signalements_dansLaFenetre_etAvantLeCurseur() {
        ReportEntity recent = report();
        ReportEntity old = report();
        backdate("reports", "created_at", old.getId(), Duration.ofDays(31));

        List<String> ids = ids(AdminNotificationType.REPORT_CREATED);
        assertThat(ids).contains("REPORT_CREATED:" + recent.getId()).doesNotContain("REPORT_CREATED:" + old.getId());

        AdminNotificationItem item = queries.find(AdminNotificationType.REPORT_CREATED, windowStart(), null, 100)
                .stream().filter(i -> i.id().endsWith(recent.getId().toString())).findFirst().orElseThrow();
        assertThat(item.summary()).contains("demande d'envoi");
        assertThat(item.severity()).isEqualTo(AdminNotificationSeverity.WARNING);

        List<AdminNotificationItem> beforeNow = queries.find(AdminNotificationType.REPORT_CREATED,
                windowStart(), item.createdAt(), 100);
        assertThat(beforeNow).extracting(AdminNotificationItem::id).doesNotContain(item.id());
        assertThat(beforeNow).allMatch(i -> i.createdAt().isBefore(item.createdAt()));
    }

    @Test
    void signalements_limiteEtTriDecroissant() {
        report();
        report();
        List<AdminNotificationItem> page = queries.find(AdminNotificationType.REPORT_CREATED, windowStart(), null, 1);
        assertThat(page).hasSize(1);
        List<AdminNotificationItem> all = queries.find(AdminNotificationType.REPORT_CREATED, windowStart(), null, 100);
        assertThat(all).isSortedAccordingTo((a, b) -> b.createdAt().compareTo(a.createdAt()));
    }

    private ReportEntity report() {
        ReportEntity r = new ReportEntity();
        r.setTargetType(ReportTargetType.PACKAGE_REQUEST);
        r.setTargetId(UUID.randomUUID());
        r.setReporterId(UUID.randomUUID());
        r.setReason(ReportReason.SCAM_ATTEMPT);
        r.setStatus(ReportStatus.OPEN);
        return persist(r);
    }

    // ---------------------------------------------------------------- support

    @Test
    void support_ticketCree_etReponsesUtilisateur_sansLePremierMessage() throws Exception {
        UserEntity awa = user("Awa", "Diallo");
        SupportTicketEntity ticket = ticket(awa.getId(), SupportTicketStatus.NEW, null);
        SupportMessageEntity first = message(ticket.getId(), SupportMessageAuthorType.USER, awa.getId());
        backdate("support_messages", "created_at", first.getId(), Duration.ofMinutes(10));
        SupportMessageEntity adminReply = message(ticket.getId(), SupportMessageAuthorType.ADMIN, UUID.randomUUID());
        backdate("support_messages", "created_at", adminReply.getId(), Duration.ofMinutes(5));
        SupportMessageEntity userReply = message(ticket.getId(), SupportMessageAuthorType.USER, awa.getId());

        List<AdminNotificationItem> tickets = queries.find(AdminNotificationType.SUPPORT_TICKET_CREATED,
                windowStart(), null, 100);
        AdminNotificationItem created = tickets.stream()
                .filter(i -> i.id().equals("SUPPORT_TICKET_CREATED:" + ticket.getId())).findFirst().orElseThrow();
        assertThat(created.summary()).contains("Awa D.").doesNotContain("Diallo");
        assertThat(created.link()).isEqualTo("/support?ticket=" + ticket.getId());

        List<String> messages = ids(AdminNotificationType.SUPPORT_MESSAGE_RECEIVED);
        assertThat(messages).contains("SUPPORT_MESSAGE_RECEIVED:" + userReply.getId())
                .doesNotContain("SUPPORT_MESSAGE_RECEIVED:" + first.getId())
                .doesNotContain("SUPPORT_MESSAGE_RECEIVED:" + adminReply.getId());
    }

    @Test
    void support_compteurs_nonResolus_parAssignation() {
        AdminUserEntity me = adminUsers.save(new AdminUserEntity("uid-notif-" + UUID.randomUUID(),
                "notif-" + UUID.randomUUID() + "@yadony.test", AdminRole.SUPPORT));
        AdminQueueSnapshot before = counter.snapshot();

        ticket(UUID.randomUUID(), SupportTicketStatus.NEW, null);
        ticket(UUID.randomUUID(), SupportTicketStatus.WAITING_SUPPORT, me.getId());
        ticket(UUID.randomUUID(), SupportTicketStatus.ASSIGNED, me.getId());
        ticket(UUID.randomUUID(), SupportTicketStatus.RESOLVED, me.getId());
        ticket(UUID.randomUUID(), SupportTicketStatus.RESOLVED, null);

        AdminQueueSnapshot after = counter.snapshot();
        assertThat(after.supportUnassigned() - before.supportUnassigned()).isEqualTo(1);
        assertThat(after.supportByAdmin().getOrDefault(me.getId(), 0L)).isEqualTo(2);
    }

    private SupportTicketEntity ticket(UUID userId, SupportTicketStatus status, UUID assignee) {
        SupportTicketEntity t = new SupportTicketEntity();
        t.setUserId(userId);
        t.setCategory("PAYMENT");
        t.setSubject("Mon numéro 0612345678");
        t.setStatus(status);
        t.setPriority(SupportPriority.NORMAL);
        t.setAssignedAdminId(assignee);
        t.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC));
        return persist(t);
    }

    private SupportMessageEntity message(UUID ticketId, SupportMessageAuthorType author, UUID authorId) {
        SupportMessageEntity m = new SupportMessageEntity();
        m.setTicketId(ticketId);
        m.setAuthorType(author);
        m.setAuthorId(authorId);
        m.setContent("bonjour");
        return persist(m);
    }

    // ------------------------------------------------------ litiges, no-shows

    @Test
    void litiges_etNoShowsAConfirmer() {
        DisputeEntity dispute = new DisputeEntity();
        dispute.setBidId(UUID.randomUUID());
        dispute.setType("DAMAGED");
        persist(dispute);
        CancellationEntity pending = cancellation(CancellationStatus.PENDING_CONFIRMATION);
        CancellationEntity confirmed = cancellation(CancellationStatus.CONFIRMED);

        assertThat(ids(AdminNotificationType.DISPUTE_OPENED)).contains("DISPUTE_OPENED:" + dispute.getId());
        assertThat(ids(AdminNotificationType.NOSHOW_PENDING)).contains("NOSHOW_PENDING:" + pending.getId())
                .doesNotContain("NOSHOW_PENDING:" + confirmed.getId());
    }

    private CancellationEntity cancellation(CancellationStatus status) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(UUID.randomUUID());
        c.setCancelledBy(UUID.randomUUID());
        c.setReason("absent");
        c.setNoShowStatus(status);
        return persist(c);
    }

    // -------------------------------------------------------------------- KYC

    @Test
    void kyc_soumissions() {
        UserEntity awa = user("Awa", "Diallo");
        KycVerificationEntity submitted = new KycVerificationEntity();
        submitted.setUserId(awa.getId());
        submitted.setStatus(KycVerificationStatus.PENDING);
        submitted.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        persist(submitted);
        KycVerificationEntity started = new KycVerificationEntity();
        started.setUserId(user("Binta", null).getId());
        started.setStatus(KycVerificationStatus.PENDING);
        persist(started);

        List<AdminNotificationItem> items = queries.find(AdminNotificationType.KYC_IN_REVIEW, windowStart(), null, 100);
        assertThat(items).extracting(AdminNotificationItem::id)
                .contains("KYC_IN_REVIEW:" + submitted.getId())
                .doesNotContain("KYC_IN_REVIEW:" + started.getId());
        AdminNotificationItem item = items.stream()
                .filter(i -> i.id().endsWith(submitted.getId().toString())).findFirst().orElseThrow();
        assertThat(item.link()).isEqualTo("/kyc?status=IN_REVIEW&open=" + awa.getId());
    }

    // --------------------------------------------------- paiements et wallet

    @Test
    void versementsRetenus_etDemandesDeRemboursementManuelles() {
        PaymentEntity held = payment(true);
        PaymentEntity free = payment(false);
        UserEntity awa = user("Awa", "Diallo");
        WalletRefundRequestEntity manual = refund(awa.getId(), WalletRefundChannel.MANUAL_ADMIN);
        WalletRefundRequestEntity automatic = refund(awa.getId(), WalletRefundChannel.AUTOMATIC_STRIPE);

        assertThat(ids(AdminNotificationType.PAYOUT_HELD)).contains("PAYOUT_HELD:" + held.getId())
                .doesNotContain("PAYOUT_HELD:" + free.getId());
        assertThat(ids(AdminNotificationType.WALLET_REFUND_REQUESTED))
                .contains("WALLET_REFUND_REQUESTED:" + manual.getId())
                .doesNotContain("WALLET_REFUND_REQUESTED:" + automatic.getId());
    }

    private PaymentEntity payment(boolean held) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setRail(PaymentRail.PAWAPAY);
        p.setStatus(PaymentStatus.ESCROW);
        p.setAmount(new BigDecimal("16800"));
        p.setCommissionAmount(new BigDecimal("1800"));
        p.setCurrency("XOF");
        if (held) {
            p.setPayoutHeldAt(LocalDateTime.now(ZoneOffset.UTC));
        }
        return persist(p);
    }

    private WalletRefundRequestEntity refund(UUID userId, WalletRefundChannel channel) {
        WalletRefundRequestEntity w = new WalletRefundRequestEntity();
        w.setUserId(userId);
        w.setCurrency(channel == WalletRefundChannel.MANUAL_ADMIN ? "EUR" : "XOF");
        w.setAmount(new BigDecimal("12.00"));
        w.setStatus(WalletRefundRequestStatus.PENDING);
        w.setChannel(channel);
        w.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        return persist(w);
    }

    // ------------------------------------------------------- RGPD et alertes

    @Test
    void rgpd_etAlertesNonResolues() {
        UserEntity leaving = user("Awa", "Diallo");
        tx.executeWithoutResult(s -> {
            UserEntity u = em.find(UserEntity.class, leaving.getId());
            u.setDeletionRequestedAt(Instant.now());
        });
        UserEntity staying = user("Binta", "Sow");

        AdminAlertEntity open = alert(false);
        AdminAlertEntity resolved = alert(true);

        assertThat(ids(AdminNotificationType.GDPR_REQUESTED)).contains("GDPR_REQUESTED:" + leaving.getId())
                .doesNotContain("GDPR_REQUESTED:" + staying.getId());
        assertThat(ids(AdminNotificationType.ADMIN_ALERT)).contains("ADMIN_ALERT:" + open.getId())
                .doesNotContain("ADMIN_ALERT:" + resolved.getId());
    }

    private AdminAlertEntity alert(boolean resolved) {
        AdminAlertEntity a = new AdminAlertEntity();
        a.setType("PAYOUT_FAILED_" + UUID.randomUUID());
        a.setSeverity("CRITICAL");
        a.setResolved(resolved);
        return persist(a);
    }

    // ------------------------------------------------------------ compteurs

    @Test
    void compteurs_desFiles_aTraiter() {
        AdminQueueSnapshot before = counter.snapshot();

        report();
        ReportEntity done = report();
        tx.executeWithoutResult(s -> em.find(ReportEntity.class, done.getId()).setStatus(ReportStatus.RESOLVED));
        payment(true);
        refund(user("Awa", "Diallo").getId(), WalletRefundChannel.MANUAL_ADMIN);
        alert(false);

        AdminQueueSnapshot after = counter.snapshot();
        assertThat(after.openReports() - before.openReports()).isEqualTo(1);
        assertThat(after.heldPayouts() - before.heldPayouts()).isEqualTo(1);
        assertThat(after.pendingWalletRefunds() - before.pendingWalletRefunds()).isEqualTo(1);
        assertThat(after.unresolvedAlerts() - before.unresolvedAlerts()).isEqualTo(1);
    }

    // ------------------------------------------------------------ mark-seen

    @Test
    void dateDeConsultation_neReculeJamais() {
        AdminUserEntity admin = adminUsers.save(new AdminUserEntity("uid-seen-" + UUID.randomUUID(),
                "seen-" + UUID.randomUUID() + "@yadony.test", AdminRole.SUPPORT));
        assertThat(adminUsers.findNotificationsSeenAt(admin.getId())).isEmpty();

        LocalDateTime t1 = LocalDateTime.of(2026, 9, 28, 10, 0);
        assertThat(adminUsers.advanceNotificationsSeenAt(admin.getId(), t1)).isEqualTo(1);
        assertThat(adminUsers.findNotificationsSeenAt(admin.getId())).contains(t1);

        assertThat(adminUsers.advanceNotificationsSeenAt(admin.getId(), t1.minusHours(1))).isZero();
        assertThat(adminUsers.findNotificationsSeenAt(admin.getId())).contains(t1);

        LocalDateTime t2 = t1.plusMinutes(5);
        adminUsers.advanceNotificationsSeenAt(admin.getId(), t2);
        assertThat(adminUsers.findNotificationsSeenAt(admin.getId())).contains(t2);
    }
}
