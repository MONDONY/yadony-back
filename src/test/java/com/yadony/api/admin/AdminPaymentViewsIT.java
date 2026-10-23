package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
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
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.chargeback.ChargebackEntity;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.chargeback.ChargebackStatus;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
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
import org.springframework.data.domain.Page;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vues, alerte unique et nouveaux filtres de la liste des paiements, sur la jointure effective
 * ({@link AdminPaymentSql#FROM_JOIN}) : chaque fragment SQL y est exécuté. Chaque test se restreint à
 * ses paiements par la recherche sur son propre pseudo d'expéditeur, ou par un identifiant unique ;
 * l'heure de référence est fixe ({@link #NOW}) et les dates sont posées relativement à elle.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminPaymentViewsIT {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    @Autowired AdminPaymentInsights insights;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired ChargebackRepository chargebackRepository;
    @Autowired PawapayOperationRepository pawapayOperationRepository;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private UserEntity sender;
    private UserEntity traveler;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        tag = "v" + UUID.randomUUID().toString().substring(0, 8);
        sender = persistUser(tag + "snd", "Awa", "Diallo");
        traveler = persistUser(tag + "trv", "Moussa", "Keita");
        trip = persistAnnouncement(traveler.getId());
    }

    @Test
    void attentionContainsHeldChargebackFailedMobileMoneyAndLateEscrow() {
        PaymentEntity held = payment(PaymentStatus.ESCROW, "10.00", NOW.minusHours(1));
        hold(held);
        PaymentEntity chargeback = payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(10));
        openChargeback(chargeback.getId(), null);
        PaymentEntity mm = payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(5));
        operation(mm.getId(), PawapayOperationKind.PAYOUT, PawapayOperationStatus.FAILED, NOW.minusHours(4));
        PaymentEntity late = payment(PaymentStatus.ESCROW, "10.00", NOW.minusDays(3));
        PaymentEntity older = payment(PaymentStatus.ESCROW, "10.00", NOW.minusDays(5));
        PaymentEntity fresh = payment(PaymentStatus.ESCROW, "10.00", NOW.minusHours(2));

        assertThat(ids(filter(AdminPaymentView.ATTENTION, null))).containsExactly(
                held.getId(), chargeback.getId(), mm.getId(), older.getId(), late.getId());
        assertThat(insights.alertRanks(List.of(held.getId(), chargeback.getId(), mm.getId(), late.getId(),
                fresh.getId()), NOW)).containsExactlyInAnyOrderEntriesOf(Map.of(
                held.getId(), 1, chargeback.getId(), 2, mm.getId(), 3, late.getId(), 4, fresh.getId(), 0));
        // Hors vue « À surveiller » : le plus récent d'abord.
        assertThat(ids(filter(AdminPaymentView.ALL, null))).containsExactly(
                held.getId(), fresh.getId(), mm.getId(), chargeback.getId(), late.getId(), older.getId());
        assertThat(ids(filter(AdminPaymentView.ALL, AdminPaymentAlert.CHARGEBACK_OPEN))).containsExactly(chargeback.getId());
        assertThat(ids(filter(AdminPaymentView.ALL, AdminPaymentAlert.MM_FAILED))).containsExactly(mm.getId());
        assertThat(ids(filter(AdminPaymentView.ALL, AdminPaymentAlert.ESCROW_J48)))
                .containsExactly(late.getId(), older.getId());
    }

    /**
     * L'étiquette (rang) ne porte que l'alerte la plus urgente, mais le filtre {@code alert=} est
     * non exclusif : un versement retenu de plus de 48 h sort sous HELD et sous ESCROW_J48, et une
     * seule fois dans « À surveiller ».
     */
    @Test
    void onlyMostUrgentAlertCounts() {
        PaymentEntity both = payment(PaymentStatus.ESCROW, "10.00", NOW.minusDays(3));
        hold(both);

        assertThat(insights.alertRanks(List.of(both.getId()), NOW)).containsEntry(both.getId(), 1);
        assertThat(ids(filter(AdminPaymentView.ALL, AdminPaymentAlert.HELD))).containsExactly(both.getId());
        assertThat(ids(filter(AdminPaymentView.ALL, AdminPaymentAlert.ESCROW_J48))).containsExactly(both.getId());
        assertThat(ids(filter(AdminPaymentView.ATTENTION, null))).containsExactly(both.getId());
        assertThat(insights.search(filter(AdminPaymentView.ATTENTION, null), NOW, PageRequest.of(0, 20))
                .getTotalElements()).isEqualTo(1);
    }

    @Test
    void failedMobileMoneyIsClearedBySuccessfulRetry() {
        PaymentEntity retried = payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(6));
        operation(retried.getId(), PawapayOperationKind.PAYOUT, PawapayOperationStatus.FAILED, NOW.minusHours(5));
        operation(retried.getId(), PawapayOperationKind.PAYOUT, PawapayOperationStatus.COMPLETED, NOW.minusHours(4));
        PaymentEntity inFlight = payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(6));
        operation(inFlight.getId(), PawapayOperationKind.PAYOUT, PawapayOperationStatus.FAILED, NOW.minusHours(5));
        operation(inFlight.getId(), PawapayOperationKind.PAYOUT, PawapayOperationStatus.PROCESSING, NOW.minusHours(4));
        PaymentEntity deposit = payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(6));
        operation(deposit.getId(), PawapayOperationKind.DEPOSIT, PawapayOperationStatus.FAILED, NOW.minusHours(5));
        PaymentEntity refund = payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(6));
        operation(refund.getId(), PawapayOperationKind.REFUND, PawapayOperationStatus.SUBMIT_REJECTED, NOW.minusHours(5));
        PaymentEntity failedAfterSuccess = payment(PaymentStatus.RELEASED, "10.00", NOW.minusHours(6));
        operation(failedAfterSuccess.getId(), PawapayOperationKind.PAYOUT, PawapayOperationStatus.COMPLETED, NOW.minusHours(5));
        operation(failedAfterSuccess.getId(), PawapayOperationKind.PAYOUT, PawapayOperationStatus.FAILED, NOW.minusHours(4));

        assertThat(insights.alertRanks(List.of(retried.getId(), inFlight.getId(), deposit.getId(), refund.getId(),
                failedAfterSuccess.getId()), NOW)).containsExactlyInAnyOrderEntriesOf(Map.of(
                retried.getId(), 0, inFlight.getId(), 0, deposit.getId(), 0, refund.getId(), 3,
                failedAfterSuccess.getId(), 3));
        assertThat(ids(filter(AdminPaymentView.ALL, AdminPaymentAlert.MM_FAILED)))
                .containsExactlyInAnyOrder(refund.getId(), failedAfterSuccess.getId());
    }

    @Test
    void chargebackWithoutPaymentIdMatchesByBid() {
        BidEntity bid = persistBid(trip.getId(), null);
        PaymentEntity direct = paymentOn(bid.getId(), null, PaymentStatus.RELEASED, "10.00", NOW.minusHours(3));
        openChargeback(null, bid.getId());
        NegotiationThreadEntity thread = persistThread();
        BidEntity linked = persistBid(trip.getId(), thread.getId());
        PaymentEntity nego = paymentOn(null, thread.getId(), PaymentStatus.RELEASED, "10.00", NOW.minusHours(2));
        openChargeback(null, linked.getId());
        BidEntity wonBid = persistBid(trip.getId(), null);
        PaymentEntity won = paymentOn(wonBid.getId(), null, PaymentStatus.RELEASED, "10.00", NOW.minusHours(1));
        ChargebackEntity closed = openChargeback(null, wonBid.getId());
        closed.setStatus(ChargebackStatus.WON);
        chargebackRepository.save(closed);

        assertThat(insights.alertRanks(List.of(direct.getId(), nego.getId(), won.getId()), NOW))
                .containsExactlyInAnyOrderEntriesOf(Map.of(direct.getId(), 2, nego.getId(), 2, won.getId(), 0));
        assertThat(ids(filter(AdminPaymentView.ALL, AdminPaymentAlert.CHARGEBACK_OPEN)))
                .containsExactly(nego.getId(), direct.getId());
    }

    @Test
    void viewsFilterByStatus() {
        PaymentEntity escrow = payment(PaymentStatus.ESCROW, "10.00", NOW.minusHours(1));
        PaymentEntity releasedRecent = payment(PaymentStatus.RELEASED, "10.00", NOW.minusDays(6));
        releasedAt(releasedRecent, NOW.minusDays(2));
        PaymentEntity releasedOld = payment(PaymentStatus.RELEASED, "10.00", NOW.minusDays(6).minusHours(1));
        releasedAt(releasedOld, NOW.minusDays(5));
        PaymentEntity releasedNoDate = payment(PaymentStatus.RELEASED, "10.00", NOW.minusDays(1));
        PaymentEntity refunded = payment(PaymentStatus.REFUNDED, "10.00", NOW.minusDays(7));
        PaymentEntity failed = payment(PaymentStatus.FAILED, "10.00", NOW.minusDays(8));
        PaymentEntity cancelled = payment(PaymentStatus.CANCELLED, "10.00", NOW.minusDays(9));
        PaymentEntity pending = payment(PaymentStatus.PENDING, "10.00", NOW.minusHours(2));

        assertThat(ids(filter(AdminPaymentView.ESCROW, null))).containsExactly(escrow.getId());
        assertThat(ids(filter(AdminPaymentView.RELEASED_RECENT, null)))
                .containsExactly(releasedNoDate.getId(), releasedRecent.getId());
        assertThat(ids(filter(AdminPaymentView.REFUNDED, null))).containsExactly(refunded.getId());
        assertThat(ids(filter(AdminPaymentView.FAILED, null))).containsExactly(failed.getId(), cancelled.getId());
        assertThat(ids(filter(AdminPaymentView.ALL, null))).containsExactly(escrow.getId(), pending.getId(),
                releasedNoDate.getId(), releasedRecent.getId(), releasedOld.getId(), refunded.getId(),
                failed.getId(), cancelled.getId());
    }

    @Test
    void linkedNegotiationBidDoesNotDuplicatePayment() {
        NegotiationThreadEntity thread = persistThread();
        BidEntity linked = persistBid(trip.getId(), thread.getId());
        PaymentEntity p = paymentOn(null, thread.getId(), PaymentStatus.ESCROW, "20.00", NOW.minusHours(1));

        AdminPaymentFilter f = filter(AdminPaymentView.ALL, null);
        Page<PaymentEntity> page = insights.search(f, NOW, PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(PaymentEntity::getId).containsExactly(p.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(insights.exportRows(f, NOW)).extracting(PaymentEntity::getId).containsExactly(p.getId());
        assertThat(insights.totals(f, NOW)).containsExactly(
                new AdminPaymentInsights.CurrencyTotals("EUR", 1, 2000, 0, 0, 200, 0));
        assertThat(ids(withBid(linked.getId()))).containsExactly(p.getId());
    }

    @Test
    void partyAnnouncementBidAndAmountFilters() {
        BidEntity direct = persistBid(trip.getId(), null);
        PaymentEntity pd = paymentOn(direct.getId(), null, PaymentStatus.ESCROW, "50.00", NOW.minusHours(2));
        AnnouncementEntity otherTrip = persistAnnouncement(traveler.getId());
        NegotiationThreadEntity thread = persistThread();
        BidEntity linked = persistBid(otherTrip.getId(), thread.getId());
        PaymentEntity pt = paymentOn(null, thread.getId(), PaymentStatus.ESCROW, "20.00", NOW.minusHours(1));
        UserEntity stranger = persistUser(tag + "x", "Ibra", "Sow");

        assertThat(ids(withParty(sender.getId()))).containsExactly(pt.getId(), pd.getId());
        assertThat(ids(withParty(traveler.getId()))).containsExactly(pt.getId(), pd.getId());
        assertThat(ids(withParty(stranger.getId()))).isEmpty();
        assertThat(ids(withAnnouncement(trip.getId()))).containsExactly(pd.getId());
        assertThat(ids(withAnnouncement(otherTrip.getId()))).containsExactly(pt.getId());
        assertThat(ids(withBid(direct.getId()))).containsExactly(pd.getId());
        assertThat(ids(withBid(linked.getId()))).containsExactly(pt.getId());
        assertThat(ids(withAmounts(2000L, 5000L))).containsExactly(pt.getId(), pd.getId());
        assertThat(ids(withAmounts(2001L, null))).containsExactly(pd.getId());
        assertThat(ids(withAmounts(null, 4999L))).containsExactly(pt.getId());
        assertThat(ids(withAmounts(5000L, 5000L))).containsExactly(pd.getId());
        assertThat(insights.totals(withAmounts(5000L, 5000L), NOW)).extracting(AdminPaymentInsights.CurrencyTotals::count)
                .containsExactly(1L);
    }

    @Test
    void hideAbandonedStillWorksWithViews() {
        PaymentEntity abandoned = payment(PaymentStatus.PENDING, "10.00", NOW.minusDays(2));
        PaymentEntity fresh = payment(PaymentStatus.PENDING, "10.00", NOW.minusHours(1));
        PaymentEntity escrow = payment(PaymentStatus.ESCROW, "10.00", NOW.minusHours(2));

        assertThat(ids(filter(AdminPaymentView.ALL, null)))
                .containsExactly(fresh.getId(), escrow.getId(), abandoned.getId());
        assertThat(ids(hidden(AdminPaymentView.ALL))).containsExactly(fresh.getId(), escrow.getId());
        assertThat(ids(hidden(AdminPaymentView.ESCROW))).containsExactly(escrow.getId());
        // Le seuil d'abandon suit l'heure passée, pas l'horloge du système.
        assertThat(insights.search(hidden(AdminPaymentView.ALL), NOW.plusDays(3), PageRequest.of(0, 20))
                .getContent()).extracting(PaymentEntity::getId).containsExactly(escrow.getId());
    }

    // ── HTTP : paramètres typés ──────────────────────────────────────────────────

    @Test
    void http_viewAlertEtIdentifiantsTypes() throws Exception {
        PaymentEntity held = payment(PaymentStatus.ESCROW, "10.00", LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        hold(held);
        payment(PaymentStatus.REFUNDED, "10.00", LocalDateTime.now(ZoneOffset.UTC).minusHours(2));

        mockMvc.perform(get("/admin/payments").with(authentication(paymentViewer()))
                        .param("q", tag + "snd").param("view", "ATTENTION").param("alert", "HELD")
                        .param("partyId", traveler.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(held.getId().toString()));
        mockMvc.perform(get("/admin/payments/summary").with(authentication(paymentViewer()))
                        .param("q", tag + "snd").param("view", "REFUNDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].count").value(1));
    }

    @Test
    void http_valeursInconnues_400() throws Exception {
        for (String[] bad : new String[][]{{"view", "NOPE"}, {"alert", "NOPE"}, {"partyId", "abc"},
                {"announcementId", "abc"}, {"bidId", "abc"}, {"amountMin", "dix"}}) {
            mockMvc.perform(get("/admin/payments").with(authentication(paymentViewer())).param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/admin/payments/summary").with(authentication(paymentViewer())).param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── Filtres ─────────────────────────────────────────────────────────────────

    private AdminPaymentFilter filter(AdminPaymentView view, AdminPaymentAlert alert) {
        return new AdminPaymentFilter(null, null, null, null, null, false, tag + "snd", false,
                view, alert, null, null, null, null, null);
    }

    private AdminPaymentFilter hidden(AdminPaymentView view) {
        return new AdminPaymentFilter(null, null, null, null, null, false, tag + "snd", true,
                view, null, null, null, null, null, null);
    }

    private static AdminPaymentFilter withParty(UUID partyId) {
        return new AdminPaymentFilter(null, null, null, null, null, false, null, false,
                AdminPaymentView.ALL, null, partyId, null, null, null, null);
    }

    private static AdminPaymentFilter withAnnouncement(UUID announcementId) {
        return new AdminPaymentFilter(null, null, null, null, null, false, null, false,
                AdminPaymentView.ALL, null, null, announcementId, null, null, null);
    }

    private static AdminPaymentFilter withBid(UUID bidId) {
        return new AdminPaymentFilter(null, null, null, null, null, false, null, false,
                AdminPaymentView.ALL, null, null, null, bidId, null, null);
    }

    private AdminPaymentFilter withAmounts(Long min, Long max) {
        return new AdminPaymentFilter(null, null, null, null, null, false, null, false,
                AdminPaymentView.ALL, null, sender.getId(), null, null, min, max);
    }

    private List<UUID> ids(AdminPaymentFilter f) {
        return insights.search(f, NOW, PageRequest.of(0, 50)).getContent().stream().map(PaymentEntity::getId).toList();
    }

    private static UsernamePasswordAuthenticationToken paymentViewer() {
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "f@yadony.test", AdminRole.SUPPORT, false, "uid-f"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority(AdminPermission.PAYMENT_VIEW.name())));
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
