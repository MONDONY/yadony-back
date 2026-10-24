package com.yadony.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
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
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cohérence transverse de la page Transactions sur UN jeu de données : les nombres (liste, comptes, section
 * du menu, carte « séquestre > 48 h » de la vue d'ensemble) disent la même chose, chaque paiement n'apparaît
 * que dans la vue qui lui revient, et aucune réponse de la page ne contient un numéro de téléphone en clair.
 * La base H2 est partagée : on se restreint à l'expéditeur du test (jeton en lettres seulement, pour ne
 * jamais produire de chiffres dans les noms) et on compare des différences pour les nombres de tout le périmètre.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminTransactionsConsistencyIT {

    private static final String MSISDN = "221771234567";
    private static final List<String> PHONE_FORMS = List.of(MSISDN, "+" + MSISDN, "771234567", "77 123 45 67");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminMetricsService metricsService;
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

    // Un paiement dans chaque état.
    private PaymentEntity held;
    private PaymentEntity j48;
    private PaymentEntity chargebackOpen;
    private PaymentEntity mmFailed;
    private PaymentEntity escrowCalm;
    private PaymentEntity releasedCalm;
    private PaymentEntity releasedOld;
    private PaymentEntity refunded;
    private PaymentEntity failed;
    private PaymentEntity cancelled;
    private PaymentEntity abandoned;

    @BeforeEach
    void setUp() {
        tag = "zq" + letters(10);
        sender = persistUser(tag + "snd");
        traveler = persistUser(tag + "trv");
        trip = persistAnnouncement(traveler.getId());
    }

    @Test
    void unSeulJeuDeDonnees_toutesLesSourcesSAccordent() throws Exception {
        JsonNode financeBefore = json("/admin/finance/counts");
        dataset();
        JsonNode financeAfter = json("/admin/finance/counts");

        // (a) attention : comptes, taille de la liste ATTENTION, détail de la section du menu.
        JsonNode counts = json("/admin/payments/counts", "q", tag + "snd");
        long attention = counts.get("attention").asLong();
        JsonNode attentionList = json("/admin/payments", "q", tag + "snd", "view", "ATTENTION", "size", "100");
        assertThat(attention).isEqualTo(4);
        assertThat(attentionList.get("totalElements").asLong()).isEqualTo(attention);
        assertThat(attentionList.get("content")).hasSize((int) attention);
        assertThat(financeAfter.at("/details/paymentsAttention").asLong()
                - financeBefore.at("/details/paymentsAttention").asLong()).isEqualTo(attention);
        assertThat(financeAfter.get("payments").asLong()).isEqualTo(financeAfter.at("/details/paymentsAttention").asLong());
        assertThat(financeAfter.at("/details/chargebacksOpen").asLong()
                - financeBefore.at("/details/chargebacksOpen").asLong()).isEqualTo(1);
        assertThat(financeAfter.at("/details/cashCommissionsAttention").asLong()
                - financeBefore.at("/details/cashCommissionsAttention").asLong()).isEqualTo(1);

        // Les autres nombres égalent la taille de leur liste ; les alertes, brutes, valent 1 chacune.
        assertThat(counts.get("escrow").asLong()).isEqualTo(3);
        assertThat(counts.get("releasedRecent").asLong()).isEqualTo(3);
        assertThat(counts.get("refunded").asLong()).isEqualTo(1);
        assertThat(counts.get("failed").asLong()).isEqualTo(2);
        assertThat(counts.get("all").asLong()).isEqualTo(11);
        assertThat(counts.get("abandoned").asLong()).isEqualTo(1);
        assertThat(counts.at("/alerts/held").asLong()).isEqualTo(1);
        assertThat(counts.at("/alerts/chargebackOpen").asLong()).isEqualTo(1);
        assertThat(counts.at("/alerts/mmFailed").asLong()).isEqualTo(1);
        assertThat(counts.at("/alerts/escrowJ48").asLong()).isEqualTo(1);

        // escrowJ48 : même nombre que la carte de la vue d'ensemble, sur tout le périmètre (table entière).
        JsonNode all = json("/admin/payments/counts");
        assertThat(all.at("/alerts/escrowJ48").asLong())
                .isEqualTo(metricsService.buildOverview().queues().escrowJ48())
                .isGreaterThanOrEqualTo(1);

        // (b) chaque paiement n'apparaît que dans les vues qui lui reviennent.
        Map<String, Set<UUID>> expected = new LinkedHashMap<>();
        expected.put("ALL", ids(held, j48, chargebackOpen, mmFailed, escrowCalm, releasedCalm, releasedOld, refunded,
                failed, cancelled, abandoned));
        expected.put("ATTENTION", ids(held, j48, chargebackOpen, mmFailed));
        expected.put("ESCROW", ids(held, j48, escrowCalm));
        expected.put("RELEASED_RECENT", ids(chargebackOpen, mmFailed, releasedCalm));
        expected.put("REFUNDED", ids(refunded));
        expected.put("FAILED", ids(failed, cancelled));
        for (Map.Entry<String, Set<UUID>> view : expected.entrySet()) {
            JsonNode list = json("/admin/payments", "q", tag + "snd", "view", view.getKey(), "size", "100");
            assertThat(contentIds(list)).as("vue %s", view.getKey()).isEqualTo(view.getValue());
            assertThat(list.get("totalElements").asLong()).as("total %s", view.getKey())
                    .isEqualTo(view.getValue().size());
        }
        // Les alertes filtrent le même périmètre que leur nombre.
        assertThat(contentIds(json("/admin/payments", "q", tag + "snd", "alert", "ESCROW_J48"))).isEqualTo(ids(j48));
        assertThat(contentIds(json("/admin/payments", "q", tag + "snd", "alert", "HELD"))).isEqualTo(ids(held));
        assertThat(contentIds(json("/admin/payments", "q", tag + "snd", "alert", "CHARGEBACK_OPEN")))
                .isEqualTo(ids(chargebackOpen));
        assertThat(contentIds(json("/admin/payments", "q", tag + "snd", "alert", "MM_FAILED"))).isEqualTo(ids(mmFailed));
    }

    @Test
    void aucuneReponseDeLaPageNeContientUnNumeroEnClair() throws Exception {
        dataset();
        List<String> bodies = new ArrayList<>();
        bodies.add(body("/admin/payments", "q", tag + "snd", "size", "100"));
        bodies.add(body("/admin/payments/" + mmFailed.getId()));
        bodies.add(body("/admin/payments/" + mmFailed.getId() + "/timeline"));
        bodies.add(body("/admin/payments/suggest", "q", tag));
        bodies.add(body("/admin/payments/export", "q", tag + "snd"));
        bodies.add(body("/admin/payments/summary", "q", tag + "snd"));
        String mobileMoney = body("/admin/mobile-money-payments", "q", tag, "size", "100");
        bodies.add(mobileMoney);
        bodies.add(body("/admin/mobile-money-payments/counts", "q", tag));
        bodies.add(body("/admin/wallets", "q", tag, "size", "100"));
        bodies.add(body("/admin/cash-commissions", "partyId", traveler.getId().toString(), "size", "100"));
        bodies.add(body("/admin/chargebacks", "q", tag, "size", "100"));
        bodies.add(body("/admin/finance/counts"));

        for (String body : bodies) {
            assertThat(body).isNotBlank();
            for (String phone : PHONE_FORMS) {
                assertThat(body).as("numéro %s en clair", phone).doesNotContain(phone);
            }
        }
        // Le numéro n'est présent que masqué, et la ligne mobile money de l'opération est bien listée.
        assertThat(mobileMoney).contains("+221 •••• 67").doesNotContain("221771234567");
        assertThat(mobileMoney).contains(mmFailed.getId().toString());
    }

    // ── Données ─────────────────────────────────────────────────────────────────

    /** Un paiement par état ; l'expéditeur du test est partout le même. */
    private void dataset() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        held = payment(PaymentStatus.ESCROW, now.minusHours(1));
        jdbc.update("UPDATE payments SET payout_held_at = ? WHERE id = ?", Timestamp.valueOf(now), held.getId());
        j48 = payment(PaymentStatus.ESCROW, now.minusHours(60));
        chargebackOpen = payment(PaymentStatus.RELEASED, now.minusHours(10));
        chargeback(chargebackOpen.getId());
        mmFailed = payment(PaymentStatus.RELEASED, now.minusHours(5));
        failedPayout(mmFailed.getId());
        escrowCalm = payment(PaymentStatus.ESCROW, now.minusHours(2));
        releasedCalm = payment(PaymentStatus.RELEASED, now.minusHours(3));
        releasedOld = payment(PaymentStatus.RELEASED, now.minusDays(9));
        refunded = payment(PaymentStatus.REFUNDED, now.minusHours(4));
        failed = payment(PaymentStatus.FAILED, now.minusHours(4));
        cancelled = payment(PaymentStatus.CANCELLED, now.minusHours(4));
        abandoned = payment(PaymentStatus.PENDING, now.minusDays(2));
        wallet(sender.getId());
        cashCommissionFailed();
    }

    private PaymentEntity payment(PaymentStatus status, LocalDateTime createdAt) {
        BidEntity bid = persistBid(null, null);
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
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

    private void chargeback(UUID paymentId) {
        ChargebackEntity c = new ChargebackEntity();
        c.setStripeDisputeId("dp_" + UUID.randomUUID().toString().replace("-", ""));
        c.setPaymentId(paymentId);
        c.setAmount(1000);
        c.setCurrency("EUR");
        c.setStatus(ChargebackStatus.OPEN);
        c.setOpenedAt(Instant.now());
        chargebackRepository.save(c);
    }

    private void failedPayout(UUID paymentId) {
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                paymentId, null, new BigDecimal("6600.00"), "XOF", "ORANGE_SEN", "SN", MSISDN);
        op.setStatus(PawapayOperationStatus.FAILED);
        pawapayOperationRepository.save(op);
    }

    private void wallet(UUID userId) {
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO wallet_accounts (id, user_id, balance, currency, refund_eligible_amount, created_at, "
                + "updated_at) VALUES (?, ?, CAST(? AS DECIMAL(10,2)), ?, 0, ?, ?)",
                UUID.randomUUID(), userId, "12.00", "EUR", Timestamp.valueOf(at), Timestamp.valueOf(at));
    }

    private void cashCommissionFailed() {
        BidEntity bid = persistBid(PaymentMethod.CASH, CommissionStatus.FAILED);
        assertThat(bid.getId()).isNotNull();
    }

    private BidEntity persistBid(PaymentMethod method, CommissionStatus commission) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        if (method == PaymentMethod.CASH) {
            bid.setPaymentMethod(PaymentMethod.CASH);
            bid.setCommissionStatus(commission);
        }
        return bidRepository.save(bid);
    }

    private UserEntity persistUser(String username) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername(username);
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new HashSet<>(Set.of(Role.SENDER, Role.TRAVELER)));
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

    // ── Appels ──────────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken admin() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        for (String permission : List.of("PAYMENT_VIEW", "BID_VIEW", "DISPUTE_VIEW", "MODERATION_VIEW", "USER_VIEW",
                "ALERT_VIEW", "AUDIT_VIEW", "EXPORT_RUN")) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "c@yadony.test", AdminRole.SUPER_ADMIN, false, "uid-c"), null,
                authorities);
    }

    private String body(String path, String... params) throws Exception {
        var request = get(path).with(authentication(admin()));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(String path, String... params) throws Exception {
        return objectMapper.readTree(body(path, params));
    }

    private static Set<UUID> ids(PaymentEntity... payments) {
        Set<UUID> ids = new HashSet<>();
        for (PaymentEntity p : payments) {
            ids.add(p.getId());
        }
        return ids;
    }

    private static Set<UUID> contentIds(JsonNode page) {
        Set<UUID> ids = new HashSet<>();
        page.get("content").forEach(row -> ids.add(UUID.fromString(row.get("id").asText())));
        return ids;
    }

    /** Lettres seulement (table hexadécimal vers g..v) : aucun chiffre dans les noms de test. */
    private static String letters(int n) {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, n);
        StringBuilder out = new StringBuilder();
        for (char c : hex.toCharArray()) {
            out.append((char) (c <= '9' ? 'g' + (c - '0') : 'q' + (c - 'a')));
        }
        return out.toString();
    }
}
