package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationRepository;
import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.CancellationStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.chargeback.ChargebackEntity;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.chargeback.ChargebackStatus;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fiche paiement : étiquette d'alerte, n° de suivi, identifiants de destination, alertes ouvertes et litige
 * bancaire, chacun soumis à la permission de sa destination (Review Focus 4 : champ ABSENT, jamais vide) ;
 * plus {@code GET /admin/alerts/{id}}. Chaque test crée ses propres paiements (base H2 partagée).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminPaymentLinksIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired CancellationRepository cancellationRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired ChargebackRepository chargebackRepository;
    @Autowired AdminAlertRepository alertRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity sender;
    private UserEntity traveler;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        String tag = "k" + UUID.randomUUID().toString().substring(0, 8);
        sender = persistUser(tag + "snd");
        traveler = persistUser(tag + "trv");
        trip = persistAnnouncement(traveler.getId());
    }

    // ── Permissions complètes ───────────────────────────────────────────────────

    @Test
    void fullAccess_directBid_allLinksAlertsAndChargeback() throws Exception {
        String tracking = "DON-" + UUID.randomUUID().toString().substring(0, 6);
        BidEntity bid = persistBid(null, tracking);
        PaymentEntity p = payment(bid.getId(), null, PaymentStatus.ESCROW, now().minusHours(2));
        hold(p);
        DisputeEntity older = dispute(bid.getId(), "DAMAGED", "RESOLVED", now().minusDays(2));
        DisputeEntity latest = dispute(bid.getId(), "NO_SHOW", "OPEN", now().minusHours(1));
        CancellationEntity cancelOld = cancellation(bid.getId(), CancellationScope.HANDOVER, now().minusDays(2));
        CancellationEntity cancel = cancellation(bid.getId(), CancellationScope.DELIVERY, now().minusHours(1));
        String firestoreId = "fs-" + UUID.randomUUID();
        conversationRepository.save(new ConversationEntity(bid.getId(), sender.getId(), traveler.getId(), firestoreId));
        ChargebackEntity cbOld = chargeback(p.getId(), null, ChargebackStatus.LOST, Instant.now().minusSeconds(7200));
        ChargebackEntity cb = chargeback(p.getId(), null, ChargebackStatus.OPEN, Instant.now().minusSeconds(60));
        AdminAlertEntity byPayload = alert("ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + p.getId() + "\"}", false,
                now().minusMinutes(30));
        AdminAlertEntity byType = alert("ESCROW_CAPTURE_FAILED_" + p.getId(), null, false, now().minusMinutes(10));
        alert("ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + p.getId() + "\"}", true, now().minusMinutes(5));
        alert("ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + UUID.randomUUID() + "\"}", false, now().minusMinutes(5));

        detail(p, allPermissions()).andExpect(status().isOk())
                // Contrat historique inchangé.
                .andExpect(jsonPath("$.id").value(p.getId().toString()))
                .andExpect(jsonPath("$.bidId").value(bid.getId().toString()))
                .andExpect(jsonPath("$.status").value("ESCROW"))
                .andExpect(jsonPath("$.amountCents").value(1000))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.insight.kind").value("BID"))
                // Ajouts.
                .andExpect(jsonPath("$.alert").value("HELD"))
                .andExpect(jsonPath("$.trackingNumber").value(tracking))
                .andExpect(jsonPath("$.links.announcementId").value(trip.getId().toString()))
                .andExpect(jsonPath("$.links.disputeId").value(latest.getId().toString()))
                .andExpect(jsonPath("$.links.disputeStatus").value("OPEN"))
                .andExpect(jsonPath("$.links.cancellationId").value(cancel.getId().toString()))
                .andExpect(jsonPath("$.links.conversationId").value(firestoreId))
                .andExpect(jsonPath("$.links.chargebackId").value(cb.getId().toString()))
                .andExpect(jsonPath("$.links.packageRequestId").doesNotExist())
                .andExpect(jsonPath("$.chargeback.id").value(cb.getId().toString()))
                .andExpect(jsonPath("$.chargeback.status").value("OPEN"))
                .andExpect(jsonPath("$.chargeback.reason").value("fraudulent"))
                .andExpect(jsonPath("$.chargeback.openedAt").exists())
                .andExpect(jsonPath("$.openAlerts.length()").value(2))
                .andExpect(jsonPath("$.openAlerts[0].id").value(byType.getId().toString()))
                .andExpect(jsonPath("$.openAlerts[0].type").value("ESCROW_CAPTURE_FAILED_" + p.getId()))
                .andExpect(jsonPath("$.openAlerts[0].severity").value("WARN"))
                .andExpect(jsonPath("$.openAlerts[1].id").value(byPayload.getId().toString()));
        // Les plus anciens ne sont pas choisis.
        detail(p, allPermissions())
                .andExpect(jsonPath("$.links.disputeId").value(org.hamcrest.Matchers.not(older.getId().toString())))
                .andExpect(jsonPath("$.links.cancellationId").value(org.hamcrest.Matchers.not(cancelOld.getId().toString())))
                .andExpect(jsonPath("$.chargeback.id").value(org.hamcrest.Matchers.not(cbOld.getId().toString())));
    }

    @Test
    void fullAccess_withoutAnything_noEmptyLinks_emptyOpenAlerts() throws Exception {
        PaymentEntity p = payment(persistBid(null, null).getId(), null, PaymentStatus.REFUNDED, now().minusHours(1));

        detail(p, allPermissions()).andExpect(status().isOk())
                .andExpect(jsonPath("$.alert").doesNotExist())
                .andExpect(jsonPath("$.trackingNumber").doesNotExist())
                .andExpect(jsonPath("$.links.announcementId").value(trip.getId().toString()))
                .andExpect(jsonPath("$.links.disputeId").doesNotExist())
                .andExpect(jsonPath("$.links.cancellationId").doesNotExist())
                .andExpect(jsonPath("$.links.conversationId").doesNotExist())
                .andExpect(jsonPath("$.links.chargebackId").doesNotExist())
                .andExpect(jsonPath("$.chargeback").doesNotExist())
                // Droit présent, aucune alerte : une liste vide, pas un champ absent.
                .andExpect(jsonPath("$.openAlerts.length()").value(0));
    }

    // ── Review Focus 4 : permissions manquantes ─────────────────────────────────

    @Test
    void supportWithoutDisputeModerationAlert_getsNoSuchLinks() throws Exception {
        BidEntity bid = persistBid(null, "DON-" + UUID.randomUUID().toString().substring(0, 6));
        PaymentEntity p = payment(bid.getId(), null, PaymentStatus.ESCROW, now().minusHours(2));
        dispute(bid.getId(), "NO_SHOW", "OPEN", now().minusHours(1));
        cancellation(bid.getId(), CancellationScope.HANDOVER, now().minusHours(1));
        conversationRepository.save(new ConversationEntity(bid.getId(), sender.getId(), traveler.getId(),
                "fs-" + UUID.randomUUID()));
        ChargebackEntity cb = chargeback(p.getId(), null, ChargebackStatus.OPEN, Instant.now());
        alert("ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + p.getId() + "\"}", false, now());

        detail(p, admin(AdminPermission.PAYMENT_VIEW, AdminPermission.BID_VIEW, AdminPermission.USER_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.announcementId").value(trip.getId().toString()))
                .andExpect(jsonPath("$.links.chargebackId").value(cb.getId().toString()))
                .andExpect(jsonPath("$.chargeback.id").value(cb.getId().toString()))
                .andExpect(jsonPath("$.links.disputeId").doesNotExist())
                .andExpect(jsonPath("$.links.disputeStatus").doesNotExist())
                .andExpect(jsonPath("$.links.cancellationId").doesNotExist())
                .andExpect(jsonPath("$.links.conversationId").doesNotExist())
                .andExpect(jsonPath("$.openAlerts").doesNotExist());
    }

    @Test
    void eachPermissionUnlocksOnlyItsOwnLinks() throws Exception {
        BidEntity bid = persistBid(null, null);
        PaymentEntity p = payment(bid.getId(), null, PaymentStatus.ESCROW, now().minusHours(2));
        dispute(bid.getId(), "NO_SHOW", "OPEN", now().minusHours(1));
        cancellation(bid.getId(), CancellationScope.HANDOVER, now().minusHours(1));
        conversationRepository.save(new ConversationEntity(bid.getId(), sender.getId(), traveler.getId(),
                "fs-" + UUID.randomUUID()));
        alert("ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + p.getId() + "\"}", false, now());

        detail(p, admin(AdminPermission.PAYMENT_VIEW, AdminPermission.DISPUTE_VIEW))
                .andExpect(jsonPath("$.links.disputeId").exists())
                .andExpect(jsonPath("$.links.disputeStatus").value("OPEN"))
                .andExpect(jsonPath("$.links.cancellationId").exists())
                .andExpect(jsonPath("$.links.conversationId").doesNotExist())
                .andExpect(jsonPath("$.links.announcementId").doesNotExist())
                .andExpect(jsonPath("$.openAlerts").doesNotExist());
        detail(p, admin(AdminPermission.PAYMENT_VIEW, AdminPermission.MODERATION_VIEW))
                .andExpect(jsonPath("$.links.conversationId").exists())
                .andExpect(jsonPath("$.links.disputeId").doesNotExist())
                .andExpect(jsonPath("$.links.cancellationId").doesNotExist())
                .andExpect(jsonPath("$.links.announcementId").doesNotExist())
                .andExpect(jsonPath("$.openAlerts").doesNotExist());
        detail(p, admin(AdminPermission.PAYMENT_VIEW, AdminPermission.ALERT_VIEW))
                .andExpect(jsonPath("$.openAlerts.length()").value(1))
                .andExpect(jsonPath("$.links").doesNotExist());
    }

    @Test
    void paymentViewOnly_noLinksObjectAtAll() throws Exception {
        BidEntity bid = persistBid(null, "DON-" + UUID.randomUUID().toString().substring(0, 6));
        PaymentEntity p = payment(bid.getId(), null, PaymentStatus.ESCROW, now().minusHours(2));
        dispute(bid.getId(), "NO_SHOW", "OPEN", now().minusHours(1));

        detail(p, admin(AdminPermission.PAYMENT_VIEW)).andExpect(status().isOk())
                .andExpect(jsonPath("$.links").doesNotExist())
                .andExpect(jsonPath("$.openAlerts").doesNotExist())
                .andExpect(jsonPath("$.chargeback").doesNotExist())
                // Le n° de suivi et l'alerte relèvent de la fiche paiement elle-même.
                .andExpect(jsonPath("$.trackingNumber").exists());
    }

    // ── Négociation ─────────────────────────────────────────────────────────────

    @Test
    void negotiationWithoutParcel_onlyPackageRequest_chargebackAndAlerts() throws Exception {
        NegotiationThreadEntity thread = persistThread();
        PaymentEntity p = payment(null, thread.getId(), PaymentStatus.ESCROW, now().minusHours(2));
        ChargebackEntity cb = chargeback(p.getId(), null, ChargebackStatus.OPEN, Instant.now());
        AdminAlertEntity a = alert("PAYOUT_HELD_" + p.getId(), null, false, now());

        detail(p, allPermissions()).andExpect(status().isOk())
                .andExpect(jsonPath("$.links.packageRequestId").value(thread.getPackageRequestId().toString()))
                .andExpect(jsonPath("$.links.chargebackId").value(cb.getId().toString()))
                .andExpect(jsonPath("$.links.announcementId").doesNotExist())
                .andExpect(jsonPath("$.links.disputeId").doesNotExist())
                .andExpect(jsonPath("$.links.cancellationId").doesNotExist())
                .andExpect(jsonPath("$.links.conversationId").doesNotExist())
                .andExpect(jsonPath("$.trackingNumber").doesNotExist())
                .andExpect(jsonPath("$.alert").value("CHARGEBACK_OPEN"))
                .andExpect(jsonPath("$.chargeback.id").value(cb.getId().toString()))
                .andExpect(jsonPath("$.openAlerts[0].id").value(a.getId().toString()));
        // packageRequestId exige BID_VIEW.
        detail(p, admin(AdminPermission.PAYMENT_VIEW, AdminPermission.DISPUTE_VIEW))
                .andExpect(jsonPath("$.links.packageRequestId").doesNotExist())
                .andExpect(jsonPath("$.links.chargebackId").value(cb.getId().toString()));
    }

    @Test
    void negotiationWithLinkedParcel_resolvesTheParcelThroughTheThread() throws Exception {
        NegotiationThreadEntity thread = persistThread();
        String tracking = "DON-" + UUID.randomUUID().toString().substring(0, 6);
        BidEntity linked = persistBid(thread.getId(), tracking);
        PaymentEntity p = payment(null, thread.getId(), PaymentStatus.ESCROW, now().minusHours(2));
        DisputeEntity d = dispute(linked.getId(), "NO_SHOW", "OPEN", now().minusHours(1));
        // Litige bancaire sans payment_id, rattaché au colis effectif du paiement.
        ChargebackEntity cb = chargeback(null, linked.getId(), ChargebackStatus.WON, Instant.now());

        detail(p, allPermissions()).andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value(tracking))
                .andExpect(jsonPath("$.links.announcementId").value(trip.getId().toString()))
                .andExpect(jsonPath("$.links.packageRequestId").value(thread.getPackageRequestId().toString()))
                .andExpect(jsonPath("$.links.disputeId").value(d.getId().toString()))
                .andExpect(jsonPath("$.links.chargebackId").value(cb.getId().toString()))
                .andExpect(jsonPath("$.chargeback.status").value("WON"));
    }

    @Test
    void openAlerts_cappedAtTwenty_newestFirst() throws Exception {
        PaymentEntity p = payment(persistBid(null, null).getId(), null, PaymentStatus.ESCROW, now().minusHours(1));
        List<AdminAlertEntity> alerts = new ArrayList<>();
        for (int i = 0; i < AdminPaymentLinks.OPEN_ALERTS_LIMIT + 1; i++) {
            alerts.add(alert("ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + p.getId() + "\",\"n\":" + i + "}", false,
                    now().minusMinutes(100 - i)));
        }

        detail(p, allPermissions())
                .andExpect(jsonPath("$.openAlerts.length()").value(AdminPaymentLinks.OPEN_ALERTS_LIMIT))
                .andExpect(jsonPath("$.openAlerts[0].id").value(alerts.get(alerts.size() - 1).getId().toString()))
                .andExpect(jsonPath("$.openAlerts[?(@.id=='" + alerts.get(0).getId() + "')]").isEmpty());
    }

    // ── GET /admin/alerts/{id} ──────────────────────────────────────────────────

    @Test
    void alertById_200_404_403() throws Exception {
        AdminAlertEntity a = alert("ESCROW_J48_TIMEOUT", "{\"paymentId\":\"" + UUID.randomUUID() + "\"}", false, now());

        mockMvc.perform(get("/admin/alerts/{id}", a.getId()).with(authentication(admin(AdminPermission.ALERT_VIEW))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.getId().toString()))
                .andExpect(jsonPath("$.type").value("ESCROW_J48_TIMEOUT"))
                .andExpect(jsonPath("$.severity").value("WARN"))
                .andExpect(jsonPath("$.resolved").value(false));
        mockMvc.perform(get("/admin/alerts/{id}", UUID.randomUUID())
                        .with(authentication(admin(AdminPermission.ALERT_VIEW))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("alert-not-found"));
        mockMvc.perform(get("/admin/alerts/{id}", a.getId())
                        .with(authentication(admin(AdminPermission.PAYMENT_VIEW))))
                .andExpect(status().isForbidden());
    }

    // ── Outils ──────────────────────────────────────────────────────────────────

    private ResultActions detail(PaymentEntity p, UsernamePasswordAuthenticationToken auth) throws Exception {
        return mockMvc.perform(get("/admin/payments/{id}", p.getId()).with(authentication(auth)));
    }

    private static UsernamePasswordAuthenticationToken allPermissions() {
        return admin(AdminPermission.PAYMENT_VIEW, AdminPermission.BID_VIEW, AdminPermission.DISPUTE_VIEW,
                AdminPermission.MODERATION_VIEW, AdminPermission.ALERT_VIEW, AdminPermission.USER_VIEW);
    }

    private static UsernamePasswordAuthenticationToken admin(AdminPermission... permissions) {
        List<GrantedAuthority> granted = new ArrayList<>();
        granted.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (AdminPermission p : permissions) granted.add(new SimpleGrantedAuthority(p.name()));
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "s@yadony.test", AdminRole.SUPPORT, false, "uid-s"), null,
                granted);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private PaymentEntity payment(UUID bidId, UUID threadId, PaymentStatus status, LocalDateTime createdAt) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency("EUR");
        p.setAmount(new BigDecimal("10.00"));
        p.setCommissionAmount(new BigDecimal("1.00"));
        p.setStatus(status);
        p = paymentRepository.save(p);
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), p.getId());
        return p;
    }

    private void hold(PaymentEntity p) {
        jdbc.update("UPDATE payments SET payout_held_at = ? WHERE id = ?", Timestamp.valueOf(now()), p.getId());
    }

    private DisputeEntity dispute(UUID bidId, String type, String status, LocalDateTime createdAt) {
        DisputeEntity d = new DisputeEntity();
        d.setBidId(bidId);
        d.setType(type);
        d.setStatus(status);
        d = disputeRepository.saveAndFlush(d);
        jdbc.update("UPDATE disputes SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), d.getId());
        return d;
    }

    private CancellationEntity cancellation(UUID bidId, CancellationScope scope, LocalDateTime createdAt) {
        CancellationEntity c = new CancellationEntity();
        c.setBidId(bidId);
        c.setCancelledBy(sender.getId());
        c.setReason("SENDER_NO_SHOW");
        c.setRefundStatus("NONE");
        c.setRematchStatus("NONE");
        c.setNoShowStatus(CancellationStatus.PENDING_CONFIRMATION);
        c.setScope(scope);
        c.setContestationDeadline(OffsetDateTime.now().plusDays(1).withNano(0));
        c = cancellationRepository.saveAndFlush(c);
        jdbc.update("UPDATE cancellations SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), c.getId());
        return c;
    }

    private ChargebackEntity chargeback(UUID paymentId, UUID bidId, ChargebackStatus status, Instant openedAt) {
        ChargebackEntity c = new ChargebackEntity();
        c.setStripeDisputeId("dp_" + UUID.randomUUID().toString().replace("-", ""));
        c.setPaymentId(paymentId);
        c.setBidId(bidId);
        c.setAmount(1000);
        c.setCurrency("EUR");
        c.setReason("fraudulent");
        c.setStatus(status);
        c.setOpenedAt(openedAt);
        return chargebackRepository.save(c);
    }

    private AdminAlertEntity alert(String type, String payload, boolean resolved, LocalDateTime createdAt) {
        AdminAlertEntity a = new AdminAlertEntity();
        a.setType(type);
        a.setPayload(payload);
        a.setSeverity("WARN");
        a.setResolved(resolved);
        a = alertRepository.saveAndFlush(a);
        jdbc.update("UPDATE admin_alerts SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), a.getId());
        return a;
    }

    private UserEntity persistUser(String username) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername(username);
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(UUID linkedThreadId, String trackingNumber) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setLinkedNegotiationThreadId(linkedThreadId);
        bid.setTrackingNumber(trackingNumber);
        return bidRepository.save(bid);
    }

    private NegotiationThreadEntity persistThread() {
        PackageRequestEntity colis = new PackageRequestEntity();
        colis.setSenderId(sender.getId());
        colis.setDepartureCity("Lyon");
        colis.setArrivalCity("Abidjan");
        colis.setDesiredDate(LocalDate.now().plusDays(10));
        colis.setDateToleranceDays((short) 3);
        colis.setWeightKg(new BigDecimal("2.50"));
        colis.setParcelSize(ParcelSize.SMALL);
        colis.setTransportMode(TransportMode.PLANE);
        colis.setContentCategory("vetements");
        colis.setStatus(PackageRequestStatus.OPEN);
        colis.setCurrency("EUR");
        colis.setNegotiable(true);
        colis.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
        packageRequestRepository.save(colis);

        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setPackageRequestId(colis.getId());
        t.setTravelerId(traveler.getId());
        t.setTravelerTravelDate(LocalDate.now().plusDays(10));
        t.setTravelerAvailableKg(new BigDecimal("5.00"));
        t.setStatus(NegotiationThreadStatus.ACCEPTED);
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setCurrency("EUR");
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return negotiationThreadRepository.save(t);
    }
}
