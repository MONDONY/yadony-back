package com.yadony.api.admin.userview;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.userview.dto.AdminUserOverviewResponse.TimelineEntry;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportTargetType;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import com.yadony.api.tracking.TrackingEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Frise « dernière activité » de la fiche utilisateur. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserTimelineIT {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Set<String> ALL = Set.of("BID_VIEW", "PAYMENT_VIEW", "DISPUTE_VIEW", "REPORT_VIEW");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired ReportRepository reportRepository;
    @Autowired TrackingEventRepository trackingEventRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUserTimelineAssembler assembler;
    @Autowired AdminUserOverviewService overviewService;

    private UserEntity traveler;
    private UserEntity sender;

    @BeforeEach
    void setUp() {
        overviewService.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        traveler = saveUser();
        sender = saveUser();
    }

    @AfterEach
    void tearDown() {
        overviewService.setClock(Clock.systemUTC());
    }

    private UserEntity saveUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken authWith(String... permissions) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) all.add(new SimpleGrantedAuthority(p));
        var principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.com", AdminRole.ADMIN, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    /** Valeur pour un UPDATE JDBC : la JVM de test applique son fuseau, on compense pour stocker de l'UTC. */
    private static LocalDateTime ts(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC).atZone(java.time.ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Valeur pour une entité JPA (déjà en UTC, sans compensation). */
    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant ago(Duration d) {
        return NOW.minus(d);
    }

    private AnnouncementEntity announcement(UserEntity owner) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(owner.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setCurrency("EUR");
        a.setAcceptedPaymentMethods(new HashSet<>(Set.of(PaymentMethod.STRIPE)));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity bid(UserEntity trav, Instant createdAt) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcement(trav).getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(BidStatus.PAYMENT_ESCROWED);
        b.setCurrency("EUR");
        b.setTrackingNumber("YD-" + UUID.randomUUID().toString().substring(0, 8));
        b = bidRepository.save(b);
        jdbc.update("UPDATE bids SET created_at = ?, updated_at = ? WHERE id = ?", ts(createdAt), ts(createdAt), b.getId());
        return b;
    }

    private PaymentEntity payment(BidEntity b, String amount, String currency, PaymentStatus st, Instant at) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(b.getId());
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency(currency);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(BigDecimal.ZERO);
        p.setStatus(st);
        p = paymentRepository.save(p);
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?", ts(at), p.getId());
        return p;
    }

    private DisputeEntity dispute(BidEntity b, Instant at) {
        DisputeEntity d = new DisputeEntity();
        d.setBidId(b.getId());
        d.setSenderId(sender.getId());
        d.setTravelerId(traveler.getId());
        d.setType(DisputeTypes.SENDER_NO_SHOW_CONTESTED);
        d.setStatus(DisputeTypes.STATUS_OPEN);
        d = disputeRepository.save(d);
        jdbc.update("UPDATE disputes SET created_at = ? WHERE id = ?", ts(at), d.getId());
        return d;
    }

    private ReportEntity report(Instant at) {
        ReportEntity r = new ReportEntity();
        r.setReporterId(sender.getId());
        r.setTargetType(ReportTargetType.USER);
        r.setTargetId(traveler.getId());
        r.setReason(ReportReason.OTHER);
        r = reportRepository.save(r);
        jdbc.update("UPDATE reports SET created_at = ? WHERE id = ?", ts(at), r.getId());
        return r;
    }

    private List<TimelineEntry> build(UserEntity u) {
        return assembler.build(u.getId(), ALL, NOW);
    }

    @Test
    void timeline_mergesSourcesNewestFirst() {
        BidEntity b = bid(traveler, ago(Duration.ofHours(5)));
        dispute(b, ago(Duration.ofHours(4)));
        report(ago(Duration.ofHours(3)));
        PaymentEntity p = payment(b, "50.00", "EUR", PaymentStatus.ESCROW, ago(Duration.ofHours(2)));

        List<TimelineEntry> timeline = build(traveler);

        assertThat(timeline).extracting(TimelineEntry::kind)
                .containsExactly("PAYMENT", "REPORT", "DISPUTE", "PARCEL");
        assertThat(timeline).extracting(TimelineEntry::title)
                .containsExactly("Paiement reçu", "Signalement reçu", "Litige ouvert", "Colis créé");
        TimelineEntry first = timeline.get(0);
        assertThat(first.refType()).isEqualTo("PAYMENT");
        assertThat(first.refId()).isEqualTo(p.getId());
        assertThat(first.detail()).isEqualTo("50 EUR");
        assertThat(first.at()).isEqualTo(ago(Duration.ofHours(2)));
        assertThat(timeline.get(3).refId()).isEqualTo(b.getId());
        assertThat(timeline.get(3).detail()).contains("Paris").contains("Dakar").contains(b.getTrackingNumber());
    }

    @Test
    void timeline_parcelHandoverDeliveryAndPaymentHeld() {
        BidEntity b = bid(traveler, ago(Duration.ofHours(30)));
        TrackingEventEntity depart = new TrackingEventEntity();
        depart.setBidId(b.getId());
        depart.setEventType(TrackingEventType.DEPART);
        depart.setScannedAt(utc(ago(Duration.ofHours(20))));
        trackingEventRepository.save(depart);
        jdbc.update("UPDATE bids SET delivered_at = ? WHERE id = ?", ts(ago(Duration.ofHours(10))), b.getId());
        PaymentEntity p = payment(b, "12.50", "EUR", PaymentStatus.RELEASED, ago(Duration.ofHours(25)));
        jdbc.update("UPDATE payments SET payout_held_at = ? WHERE id = ?", ts(ago(Duration.ofHours(9))), p.getId());

        assertThat(build(traveler)).extracting(TimelineEntry::title).containsExactly(
                "Versement retenu", "Colis livré", "Colis pris en charge", "Paiement reçu", "Colis créé");
        assertThat(build(traveler).get(0).detail()).isEqualTo("12,50 EUR");
    }

    @Test
    void timeline_includesEverythingFromLast72h() {
        for (int i = 0; i < 10; i++) report(ago(Duration.ofMinutes(10 + i)));
        ReportEntity inside = report(ago(Duration.ofHours(71).plusMinutes(59)));
        ReportEntity outside = report(ago(Duration.ofHours(72).plusMinutes(1)));

        List<TimelineEntry> timeline = build(traveler);

        assertThat(timeline).hasSize(11);
        assertThat(timeline).extracting(TimelineEntry::refId).contains(inside.getId()).doesNotContain(outside.getId());
    }

    @Test
    void timeline_keepsAllTwelveEventsOfTheLastHour() {
        for (int i = 0; i < 12; i++) report(ago(Duration.ofMinutes(i + 1)));
        report(ago(Duration.ofDays(10)));

        assertThat(build(traveler)).hasSize(12);
    }

    @Test
    void timeline_padsToTenMostRecentWhenQuiet() {
        ReportEntity recent = report(ago(Duration.ofHours(1)));
        List<ReportEntity> older = new ArrayList<>();
        for (int i = 0; i < 14; i++) older.add(report(ago(Duration.ofDays(5 + i))));

        List<TimelineEntry> timeline = build(traveler);

        assertThat(timeline).hasSize(10);
        assertThat(timeline.get(0).refId()).isEqualTo(recent.getId());
        assertThat(timeline.get(9).refId()).isEqualTo(older.get(8).getId());
    }

    @Test
    void timeline_tieBreaksOnRefIdDeterministically() {
        Instant at = ago(Duration.ofHours(1));
        UUID a = report(at).getId();
        UUID b = report(at).getId();

        List<UUID> expected = List.of(a, b).stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        assertThat(build(traveler)).extracting(TimelineEntry::refId).containsExactlyElementsOf(expected);
    }

    @Test
    void timeline_ignoresSourcesWithoutPermission() {
        BidEntity b = bid(traveler, ago(Duration.ofHours(5)));
        payment(b, "50.00", "EUR", PaymentStatus.ESCROW, ago(Duration.ofHours(2)));
        report(ago(Duration.ofHours(3)));
        dispute(b, ago(Duration.ofHours(4)));

        assertThat(assembler.build(traveler.getId(), Set.of("BID_VIEW", "REPORT_VIEW"), NOW))
                .extracting(TimelineEntry::kind).containsExactly("REPORT", "PARCEL");
        assertThat(assembler.build(traveler.getId(), Set.of("PAYMENT_VIEW"), NOW))
                .extracting(TimelineEntry::kind).containsExactly("PAYMENT");
        assertThat(assembler.build(traveler.getId(), Set.of("USER_VIEW"), NOW)).isEmpty();
    }

    @Test
    void timeline_newUserIsEmpty() {
        assertThat(build(saveUser())).isEmpty();
    }

    @Test
    void overview_includesTimeline() throws Exception {
        report(ago(Duration.ofHours(3)));
        mockMvc.perform(get("/admin/users/{id}/overview", traveler.getId())
                        .with(authentication(authWith("USER_VIEW", "REPORT_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeline.length()").value(1))
                .andExpect(jsonPath("$.timeline[0].kind").value("REPORT"))
                .andExpect(jsonPath("$.timeline[0].title").value("Signalement reçu"))
                .andExpect(jsonPath("$.timeline[0].refType").value("REPORT"));
        mockMvc.perform(get("/admin/users/{id}/overview", traveler.getId())
                        .with(authentication(authWith("USER_VIEW"))))
                .andExpect(jsonPath("$.timeline.length()").value(0));
    }
}
