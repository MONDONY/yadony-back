package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminPaymentCountsResponse;
import com.yadony.api.admin.metrics.AdminMetricsService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
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
import com.yadony.api.payments.chargeback.ChargebackEntity;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.chargeback.ChargebackStatus;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nombres par vue des paiements ({@code GET /admin/payments/counts}) : chaque nombre égale la taille de la
 * liste de la vue correspondante sur le même périmètre. Chaque test se restreint à ses données par son
 * propre pseudo d'expéditeur ; l'heure de référence est fixe ({@link #NOW}) sauf pour la comparaison avec
 * la carte {@code escrowJ48} de la vue d'ensemble, qui lit l'horloge système.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminPaymentCountsIT {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    @Autowired AdminPaymentInsights insights;
    @Autowired AdminMetricsService metricsService;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ChargebackRepository chargebackRepository;
    @Autowired PawapayOperationRepository pawapayOperationRepository;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private UserEntity sender;
    private UserEntity traveler;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        tag = "c" + UUID.randomUUID().toString().substring(0, 8);
        sender = persistUser(tag + "snd", "Awa", "Diallo");
        traveler = persistUser(tag + "trv", "Moussa", "Keita");
        trip = persistAnnouncement(traveler.getId());
    }

    /** Jeu varié : chaque vue et chaque alerte a au moins un paiement, un séquestre retenu de plus de 48 h recoupe deux alertes. */
    private void variedDataset() {
        hold(payment(PaymentStatus.ESCROW, "10.00", NOW.minusHours(1)));                 // HELD
        hold(payment(PaymentStatus.ESCROW, "10.00", NOW.minusDays(4)));                  // HELD + ESCROW_J48
        openChargeback(payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(10)).getId());
        operation(payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(5)).getId(),
                PawapayOperationKind.PAYOUT, PawapayOperationStatus.FAILED, NOW.minusHours(4));
        payment(PaymentStatus.ESCROW, "10.00", NOW.minusDays(3));                        // ESCROW_J48
        payment(PaymentStatus.ESCROW, "10.00", NOW.minusHours(2));
        PaymentEntity recent = payment(PaymentStatus.RELEASED, "10.00", NOW.minusDays(6));
        jdbc.update("UPDATE payments SET escrow_released_at = ? WHERE id = ?", Timestamp.valueOf(NOW.minusDays(2)), recent.getId());
        payment(PaymentStatus.REFUNDED, "10.00", NOW.minusDays(7));
        payment(PaymentStatus.FAILED, "10.00", NOW.minusDays(8));
        payment(PaymentStatus.CANCELLED, "10.00", NOW.minusDays(9));
        payment(PaymentStatus.PENDING, "10.00", NOW.minusHours(2));
        payment(PaymentStatus.PENDING, "10.00", NOW.minusDays(2));                        // abandonné
        payment(PaymentStatus.PENDING, "10.00", NOW.minusDays(5));                        // abandonné
    }

    @Test
    void everyCountEqualsTheSizeOfTheCorrespondingList() {
        variedDataset();

        AdminPaymentCountsResponse c = insights.counts(base(), NOW);

        assertThat(c.attention()).isEqualTo(total(view(AdminPaymentView.ATTENTION)));
        assertThat(c.escrow()).isEqualTo(total(view(AdminPaymentView.ESCROW)));
        assertThat(c.releasedRecent()).isEqualTo(total(view(AdminPaymentView.RELEASED_RECENT)));
        assertThat(c.refunded()).isEqualTo(total(view(AdminPaymentView.REFUNDED)));
        assertThat(c.failed()).isEqualTo(total(view(AdminPaymentView.FAILED)));
        assertThat(c.all()).isEqualTo(total(view(AdminPaymentView.ALL)));
        assertThat(c.alerts().held()).isEqualTo(total(alert(AdminPaymentAlert.HELD)));
        assertThat(c.alerts().chargebackOpen()).isEqualTo(total(alert(AdminPaymentAlert.CHARGEBACK_OPEN)));
        assertThat(c.alerts().mmFailed()).isEqualTo(total(alert(AdminPaymentAlert.MM_FAILED)));
        assertThat(c.alerts().escrowJ48()).isEqualTo(total(alert(AdminPaymentAlert.ESCROW_J48)));
        // Valeurs attendues du jeu : 5 paiements portent une alerte, le séquestre retenu de 4 jours en porte deux.
        assertThat(c.attention()).isEqualTo(5);
        assertThat(c.alerts().held()).isEqualTo(2);
        assertThat(c.alerts().chargebackOpen()).isEqualTo(1);
        assertThat(c.alerts().mmFailed()).isEqualTo(1);
        assertThat(c.alerts().escrowJ48()).isEqualTo(2);
        assertThat(c.alerts().held() + c.alerts().chargebackOpen() + c.alerts().mmFailed() + c.alerts().escrowJ48())
                .isGreaterThan(c.attention());
        assertThat(c.escrow()).isEqualTo(4);
        assertThat(c.releasedRecent()).isEqualTo(3);
        assertThat(c.refunded()).isEqualTo(1);
        assertThat(c.failed()).isEqualTo(2);
        assertThat(c.all()).isEqualTo(13);
        assertThat(c.abandoned()).isEqualTo(2);
    }

    @Test
    void abandonedIsCountedEvenWhenHiddenAndExcludedFromTheOtherCounts() {
        variedDataset();

        AdminPaymentCountsResponse shown = insights.counts(base(), NOW);
        AdminPaymentCountsResponse hidden = insights.counts(withHideAbandoned(base()), NOW);

        assertThat(shown.all()).isEqualTo(13);
        assertThat(shown.abandoned()).isEqualTo(2);
        assertThat(hidden.all()).isEqualTo(11);
        assertThat(hidden.abandoned()).isEqualTo(2);
        assertThat(hidden.all()).isEqualTo(total(withHideAbandoned(view(AdminPaymentView.ALL))));
        assertThat(hidden.escrow()).isEqualTo(shown.escrow());
        assertThat(hidden.attention()).isEqualTo(shown.attention());
    }

    @Test
    void viewAlertAndStatusAreIgnoredButOtherFiltersApply() {
        variedDataset();
        AdminPaymentFilter noisy = new AdminPaymentFilter("FAILED", null, null, null, null, false, tag + "snd", false,
                AdminPaymentView.REFUNDED, AdminPaymentAlert.HELD, null, null, null, null, null);
        assertThat(insights.counts(noisy, NOW)).isEqualTo(insights.counts(base(), NOW));

        // currency : tout est en EUR
        assertThat(insights.counts(with("XOF", tag + "snd", null, null, null), NOW).all()).isZero();
        assertThat(insights.counts(with("EUR", tag + "snd", null, null, null), NOW).all()).isEqualTo(13);
        // q : une autre saisie ne trouve rien
        assertThat(insights.counts(with(null, tag + "zzz", null, null, null), NOW).all()).isZero();
        // partyId : le voyageur voit les mêmes paiements, un inconnu aucun
        assertThat(insights.counts(with(null, null, traveler.getId(), null, null), NOW).all()).isEqualTo(13);
        assertThat(insights.counts(with(null, null, UUID.randomUUID(), null, null), NOW).all()).isZero();
        // montants
        assertThat(insights.counts(with(null, tag + "snd", null, 1001L, null), NOW).all()).isZero();
        assertThat(insights.counts(with(null, tag + "snd", null, 1000L, 1000L), NOW).all()).isEqualTo(13);
    }

    @Test
    void emptyPerimeterGivesZeros() {
        AdminPaymentCountsResponse c = insights.counts(base(), NOW);
        assertThat(c).isEqualTo(new AdminPaymentCountsResponse(0, 0, 0, 0, 0, 0, 0,
                new AdminPaymentCountsResponse.Alerts(0, 0, 0, 0)));
    }

    /** Review Focus 5 : la carte escrowJ48 de la vue d'ensemble et les nombres disent la même chose. */
    @Test
    void escrowJ48EqualsTheOverviewCardAndTheOlderThan48hEscrowCount() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        payment(PaymentStatus.ESCROW, "10.00", now.minusHours(60));
        hold(payment(PaymentStatus.ESCROW, "10.00", now.minusDays(5)));
        payment(PaymentStatus.ESCROW, "10.00", now.minusHours(20));
        payment(PaymentStatus.RELEASED, "10.00", now.minusDays(9));

        AdminPaymentFilter everything = new AdminPaymentFilter(null, null, null, null, null, false, null, false,
                AdminPaymentView.ALL, null, null, null, null, null, null);
        AdminPaymentCountsResponse c = insights.counts(everything, LocalDateTime.now(ZoneOffset.UTC));

        long card = metricsService.buildOverview().queues().escrowJ48();
        Long direct = jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE deleted_at IS NULL AND status = 'ESCROW'"
                + " AND created_at < ?", Long.class, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusHours(48)));
        assertThat(c.alerts().escrowJ48()).isEqualTo(card).isEqualTo(direct).isGreaterThanOrEqualTo(2);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────────

    @Test
    void http_counts() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        hold(payment(PaymentStatus.ESCROW, "10.00", now.minusHours(1)));
        payment(PaymentStatus.REFUNDED, "10.00", now.minusHours(2));
        payment(PaymentStatus.PENDING, "10.00", now.minusDays(2));

        mockMvc.perform(get("/admin/payments/counts").with(authentication(viewer(AdminPermission.PAYMENT_VIEW)))
                        .param("q", tag + "snd").param("hideAbandoned", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attention").value(1))
                .andExpect(jsonPath("$.escrow").value(1))
                .andExpect(jsonPath("$.refunded").value(1))
                .andExpect(jsonPath("$.all").value(2))
                .andExpect(jsonPath("$.abandoned").value(1))
                .andExpect(jsonPath("$.alerts.held").value(1))
                .andExpect(jsonPath("$.alerts.escrowJ48").value(0));
        mockMvc.perform(get("/admin/payments/counts").with(authentication(viewer(AdminPermission.PAYMENT_VIEW)))
                        .param("q", tag + "snd").param("partyId", traveler.getId().toString())
                        .param("view", "FAILED").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.all").value(3));
    }

    @Test
    void http_withoutPaymentView_403() throws Exception {
        mockMvc.perform(get("/admin/payments/counts").with(authentication(viewer(AdminPermission.USER_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    void http_invalidTypedParams_400() throws Exception {
        for (String[] bad : new String[][]{{"partyId", "abc"}, {"announcementId", "abc"}, {"bidId", "abc"},
                {"amountMin", "dix"}, {"hideAbandoned", "peutetre"}}) {
            mockMvc.perform(get("/admin/payments/counts").with(authentication(viewer(AdminPermission.PAYMENT_VIEW)))
                            .param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── Filtres ──────────────────────────────────────────────────────────────────

    private AdminPaymentFilter base() {
        return new AdminPaymentFilter(null, null, null, null, null, false, tag + "snd", false,
                AdminPaymentView.ALL, null, null, null, null, null, null);
    }

    private AdminPaymentFilter view(AdminPaymentView v) {
        return new AdminPaymentFilter(null, null, null, null, null, false, tag + "snd", false,
                v, null, null, null, null, null, null);
    }

    private AdminPaymentFilter alert(AdminPaymentAlert a) {
        return new AdminPaymentFilter(null, null, null, null, null, false, tag + "snd", false,
                AdminPaymentView.ALL, a, null, null, null, null, null);
    }

    private static AdminPaymentFilter withHideAbandoned(AdminPaymentFilter f) {
        return new AdminPaymentFilter(f.status(), f.from(), f.to(), f.rail(), f.currency(), f.held(), f.query(), true,
                f.view(), f.alert(), f.partyId(), f.announcementId(), f.bidId(), f.amountMin(), f.amountMax());
    }

    private static AdminPaymentFilter with(String currency, String q, UUID partyId, Long min, Long max) {
        return new AdminPaymentFilter(null, null, null, null, currency, false, q, false,
                AdminPaymentView.ALL, null, partyId, null, null, min, max);
    }

    private long total(AdminPaymentFilter f) {
        return insights.search(f, NOW, PageRequest.of(0, 50)).getTotalElements();
    }

    private static UsernamePasswordAuthenticationToken viewer(AdminPermission permission) {
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "c@yadony.test", AdminRole.SUPPORT, false, "uid-c"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority(permission.name())));
    }

    // ── Données ─────────────────────────────────────────────────────────────────

    /** Paiement d'un colis direct neuf de l'expéditeur du test, créé à {@code createdAt}. */
    private PaymentEntity payment(PaymentStatus status, String amount, LocalDateTime createdAt) {
        return paymentOn(persistBid(trip.getId(), null).getId(), null, status, amount, createdAt);
    }

    private PaymentEntity paymentOn(UUID bidId, UUID threadId, PaymentStatus status, String amount,
                                    LocalDateTime createdAt) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency("EUR");
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(amount).divide(BigDecimal.TEN));
        p.setStatus(status);
        p = paymentRepository.save(p);
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), p.getId());
        return p;
    }

    private void hold(PaymentEntity p) {
        jdbc.update("UPDATE payments SET payout_held_at = ? WHERE id = ?", Timestamp.valueOf(NOW), p.getId());
    }

    private void releasedAt(PaymentEntity p, LocalDateTime at) {
        jdbc.update("UPDATE payments SET escrow_released_at = ? WHERE id = ?", Timestamp.valueOf(at), p.getId());
    }

    private ChargebackEntity openChargeback(UUID paymentId) {
        return openChargeback(paymentId, null);
    }

    private ChargebackEntity openChargeback(UUID paymentId, UUID bidId) {
        ChargebackEntity c = new ChargebackEntity();
        c.setStripeDisputeId("dp_" + UUID.randomUUID().toString().replace("-", ""));
        c.setPaymentId(paymentId);
        c.setBidId(bidId);
        c.setAmount(1000);
        c.setCurrency("EUR");
        c.setStatus(ChargebackStatus.OPEN);
        c.setOpenedAt(Instant.now());
        return chargebackRepository.save(c);
    }

    private void operation(UUID paymentId, PawapayOperationKind kind, PawapayOperationStatus status,
                           LocalDateTime createdAt) {
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), kind, paymentId, null,
                new BigDecimal("6600.00"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        op.setStatus(status);
        op = pawapayOperationRepository.save(op);
        jdbc.update("UPDATE pawapay_operations SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt),
                op.getId());
    }

    private UserEntity persistUser(String username, String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername(username);
        u.setFirstName(firstName);
        u.setLastName(lastName);
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

    private BidEntity persistBid(UUID announcementId, UUID linkedThreadId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setLinkedNegotiationThreadId(linkedThreadId);
        return bidRepository.save(bid);
    }
}
