package com.yadony.api.admin;

import com.yadony.api.admin.metrics.AdminQueueCounter;
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
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.chargeback.ChargebackEntity;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.chargeback.ChargebackStatus;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nombres des quatre sections Transactions ({@code GET /admin/finance/counts}). La base H2 est partagée : on
 * compare des différences avant/après un jeu de données propre au test, et chaque nombre à celui du point
 * d'entrée de sa section (mêmes définitions, un seul calcul).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminFinanceCountsIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminQueueCounter queueCounter;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ChargebackRepository chargebackRepository;
    @Autowired PawapayOperationRepository pawapayOperationRepository;
    @Autowired WalletRefundRequestRepository walletRefundRequestRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity sender;
    private UserEntity traveler;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        String tag = "fc" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        sender = persistUser(tag + "snd");
        traveler = persistUser(tag + "trv");
        trip = persistAnnouncement(traveler.getId());
    }

    @Test
    void chaqueNombreSuitSaSection_etLesDetailsSontCoherents() throws Exception {
        JsonNode before = counts();

        // Paiements : un séquestre retenu (HELD) et un paiement libéré dont le versement mobile money a échoué.
        PaymentEntity held = payment(PaymentStatus.ESCROW);
        jdbc.update("UPDATE payments SET payout_held_at = CURRENT_TIMESTAMP WHERE id = ?", held.getId());
        PaymentEntity released = payment(PaymentStatus.RELEASED);
        failedPayout(released.getId());
        // Soldes : une demande de remboursement du solde en attente + une commission cash en échec.
        pendingWalletRefund();
        cashCommission(CommissionStatus.FAILED);
        cashCommission(CommissionStatus.CHARGED);
        // Litiges : un litige ouvert, un litige gagné.
        chargeback(ChargebackStatus.OPEN);
        chargeback(ChargebackStatus.WON);

        JsonNode after = counts();

        assertThat(delta(before, after, "details", "paymentsAttention")).isEqualTo(2);
        assertThat(delta(before, after, "details", "mobileMoneyAttention")).isEqualTo(1);
        assertThat(delta(before, after, "details", "walletRefundsPending")).isEqualTo(1);
        assertThat(delta(before, after, "details", "cashCommissionsAttention")).isEqualTo(1);
        assertThat(delta(before, after, "details", "chargebacksOpen")).isEqualTo(1);

        // Contrat : les quatre nombres sont ceux des détails.
        long paymentsAttention = after.at("/details/paymentsAttention").asLong();
        long mobileMoneyAttention = after.at("/details/mobileMoneyAttention").asLong();
        long walletRefunds = after.at("/details/walletRefundsPending").asLong();
        long cashAttention = after.at("/details/cashCommissionsAttention").asLong();
        long chargebacksOpen = after.at("/details/chargebacksOpen").asLong();
        assertThat(after.get("payments").asLong()).isEqualTo(paymentsAttention);
        assertThat(after.get("mobileMoney").asLong()).isEqualTo(mobileMoneyAttention);
        assertThat(after.get("soldes").asLong()).isEqualTo(walletRefunds + cashAttention);
        assertThat(after.get("chargebacks").asLong()).isEqualTo(chargebacksOpen);

        // Chaque détail égale le nombre du point d'entrée de sa section.
        assertThat(paymentsAttention).isEqualTo(json("/admin/payments/counts", "hideAbandoned", "true")
                .get("attention").asLong());
        assertThat(mobileMoneyAttention).isEqualTo(json("/admin/mobile-money-payments/counts").get("attention").asLong());
        assertThat(cashAttention).isEqualTo(json("/admin/cash-commissions/counts").get("attention").asLong());
        assertThat(chargebacksOpen).isEqualTo(json("/admin/chargebacks/counts").get("open").asLong());
        // Même nombre que la pastille du menu.
        assertThat(walletRefunds).isEqualTo(queueCounter.countPendingWalletRefunds());
    }

    @Test
    void sansPaymentView_403() throws Exception {
        var noPermission = new UsernamePasswordAuthenticationToken("x", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mockMvc.perform(get("/admin/finance/counts").with(authentication(noPermission)))
                .andExpect(status().isForbidden());
        var sender = new UsernamePasswordAuthenticationToken("s", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
        mockMvc.perform(get("/admin/finance/counts").with(authentication(sender)))
                .andExpect(status().isForbidden());
    }

    // ── Aides ───────────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken("admin-uid", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_VIEW")));
    }

    private JsonNode counts() throws Exception {
        return json("/admin/finance/counts");
    }

    private JsonNode json(String path, String... params) throws Exception {
        var request = get(path).with(authentication(admin()));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private static long delta(JsonNode before, JsonNode after, String group, String field) {
        return after.get(group).get(field).asLong() - before.get(group).get(field).asLong();
    }

    private PaymentEntity payment(PaymentStatus status) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid = bidRepository.save(bid);
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency("EUR");
        p.setAmount(new BigDecimal("10.00"));
        p.setCommissionAmount(new BigDecimal("1.00"));
        p.setStatus(status);
        return paymentRepository.save(p);
    }

    private void failedPayout(UUID paymentId) {
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                paymentId, null, new BigDecimal("6600.00"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        op.setStatus(PawapayOperationStatus.FAILED);
        pawapayOperationRepository.save(op);
    }

    private void pendingWalletRefund() {
        WalletRefundRequestEntity r = new WalletRefundRequestEntity();
        r.setUserId(sender.getId());
        r.setCurrency("EUR");
        r.setAmount(new BigDecimal("5.00"));
        r.setStatus(WalletRefundRequestStatus.PENDING);
        r.setRequestedAt(LocalDateTime.now(ZoneOffset.UTC));
        walletRefundRequestRepository.save(r);
    }

    private void cashCommission(CommissionStatus commission) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setPaymentMethod(PaymentMethod.CASH);
        bid.setCommissionStatus(commission);
        bidRepository.save(bid);
    }

    private void chargeback(ChargebackStatus status) {
        ChargebackEntity c = new ChargebackEntity();
        c.setStripeDisputeId("dp_" + UUID.randomUUID().toString().replace("-", ""));
        c.setAmount(1000);
        c.setCurrency("EUR");
        c.setStatus(status);
        c.setOpenedAt(Instant.now());
        chargebackRepository.save(c);
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
}
