package com.yadony.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.disputes.DisputeEntity;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /admin/bids/counts} de bout en bout (MockMvc + base H2 partagée). Chaque test se restreint
 * à ses propres colis (annonce ou jeton unique) : sans filtre, les nombres incluraient ceux des autres tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminBidsCountsIT — GET /admin/bids/counts")
class AdminBidsCountsIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity traveler;
    private UserEntity sender;
    private AnnouncementEntity trip;
    private String token;

    @BeforeEach
    void setUp() {
        token = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        traveler = saveUser("Mamadou", "Trav" + token);
        sender = saveUser("Aicha", "Send" + token);
        trip = announcement();
    }

    // ---------------------------------------------------------------- helpers

    private static UsernamePasswordAuthenticationToken as(AdminRole role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (AdminPermission p : role.permissions()) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "a@yadony.test", role, false, "uid-" + role), null, authorities);
    }

    private static UsernamePasswordAuthenticationToken authWith(AdminPermission... permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (AdminPermission p : permissions) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "s@yadony.test", AdminRole.SUPPORT, false, "uid-s"),
                null, authorities);
    }

    private UserEntity saveUser(String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        // Nom d'utilisateur en lettres seules : le défaut (« user » + epoch + hexa) peut contenir « 345 »
        // et faire matcher les recherches de numéro qui doivent ne rien trouver.
        u.setUsername("u" + UUID.randomUUID().toString().replace("-", "").chars().limit(16)
                .mapToObj(c -> String.valueOf((char) ('g' + Character.digit(c, 16))))
                .collect(java.util.stream.Collectors.joining()));
        u.setFirstName(firstName);
        u.setLastName(lastName);
        return userRepository.save(u);
    }

    private AnnouncementEntity announcement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Départ");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Arrivée");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setCurrency("EUR");
        a.setAcceptedPaymentMethods(new HashSet<>(Set.of(PaymentMethod.CASH)));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity bid(BidStatus status) {
        return bid(status, b -> { });
    }

    private BidEntity bid(BidStatus status, Consumer<BidEntity> customize) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(trip.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setCurrency("EUR");
        b.setTrackingNumber("YD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        customize.accept(b);
        return bidRepository.save(b);
    }

    private void openDispute(BidEntity b) {
        DisputeEntity d = new DisputeEntity();
        d.setBidId(b.getId());
        d.setStatus("OPEN");
        disputeRepository.save(d);
    }

    private void deliveredAt(BidEntity b, LocalDateTime when) {
        jdbc.update("update bids set delivered_at = ? where id = ?", when, b.getId());
    }

    /** Un jeu de colis qui touche chaque vue et chaque alerte (plus un fil de négociation exclu). */
    private void seed() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        openDispute(bid(BidStatus.ACCEPTED));                                       // alerte 1
        bid(BidStatus.NO_SHOW);                                                     // alerte 2, fermé
        BidEntity unpaid = bid(BidStatus.AWAITING_PAYMENT);                         // alerte 3
        jdbc.update("update bids set updated_at = ? where id = ?", now.minusHours(30), unpaid.getId());
        bid(BidStatus.IN_TRANSIT);                                                  // alerte 4 (trajet échu)
        jdbc.update("update announcements set arrival_date = ? where id = ?", LocalDate.now().minusDays(5), trip.getId());
        bid(BidStatus.PENDING);                                                     // actif sans alerte
        deliveredAt(bid(BidStatus.COMPLETED), now.minusHours(2));                   // récent
        deliveredAt(bid(BidStatus.COMPLETED), now.minusDays(10));                   // livré, pas récent
        bid(BidStatus.CANCELLED);                                                   // fermé
        bid(BidStatus.NEGOTIATING);                                                 // jamais un colis
    }

    private MockHttpServletRequestBuilder counts(UsernamePasswordAuthenticationToken auth, String... params) {
        var req = get("/admin/bids/counts").with(authentication(auth)).param("announcementId", trip.getId().toString());
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return req;
    }

    private JsonNode json(MockHttpServletRequestBuilder req) throws Exception {
        return objectMapper.readTree(mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private long listTotal(String view, String... params) throws Exception {
        var req = get("/admin/bids").with(authentication(as(AdminRole.ADMIN)))
                .param("announcementId", trip.getId().toString()).param("view", view);
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return json(req).get("totalElements").asLong();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void counts_matchListSizes_forEachView() throws Exception {
        seed();
        for (String[] filters : new String[][] {{}, {"query", "Send" + token}, {"paymentMethod", "CASH"}}) {
            JsonNode c = json(counts(as(AdminRole.ADMIN), filters));
            assertThat(c.get("todo").asLong()).isEqualTo(listTotal("TODO", filters));
            assertThat(c.get("active").asLong()).isEqualTo(listTotal("ACTIVE", filters));
            assertThat(c.get("recent").asLong()).isEqualTo(listTotal("RECENT", filters));
            assertThat(c.get("completed").asLong()).isEqualTo(listTotal("COMPLETED", filters));
            assertThat(c.get("closed").asLong()).isEqualTo(listTotal("CLOSED", filters));
            assertThat(c.get("all").asLong()).isEqualTo(listTotal("ALL", filters));
        }
        JsonNode c = json(counts(as(AdminRole.ADMIN)));
        assertThat(c.get("todo").asLong()).isEqualTo(4);
        assertThat(c.get("active").asLong()).isEqualTo(4);
        assertThat(c.get("recent").asLong()).isEqualTo(1);
        assertThat(c.get("completed").asLong()).isEqualTo(2);
        assertThat(c.get("closed").asLong()).isEqualTo(2);
        assertThat(c.get("all").asLong()).isEqualTo(8);
    }

    @Test
    void counts_respectSearchAndFilters() throws Exception {
        seed();
        mockMvc.perform(counts(as(AdminRole.ADMIN), "query", "Absent" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.all").value(0))
                .andExpect(jsonPath("$.todo").value(0));
        mockMvc.perform(counts(as(AdminRole.ADMIN), "query", "Aicha Send" + token))
                .andExpect(jsonPath("$.all").value(8));
        mockMvc.perform(counts(as(AdminRole.ADMIN), "paymentMethod", "MOBILE_MONEY"))
                .andExpect(jsonPath("$.all").value(0));
        mockMvc.perform(counts(as(AdminRole.ADMIN), "dateFrom", "2999-01-01T00:00:00"))
                .andExpect(jsonPath("$.all").value(0));
        mockMvc.perform(counts(as(AdminRole.ADMIN), "dateTo", "2999-01-01T00:00:00"))
                .andExpect(jsonPath("$.all").value(8));
    }

    @Test
    void counts_alertsBreakdown_sumsToTodoOrMore() throws Exception {
        seed();
        BidEntity both = bid(BidStatus.NO_SHOW);
        openDispute(both); // litige + no-show : une seule alerte, la plus urgente
        JsonNode c = json(counts(as(AdminRole.ADMIN)));
        JsonNode alerts = c.get("alerts");
        assertThat(alerts.get("disputeOpen").asLong()).isEqualTo(2);
        assertThat(alerts.get("noShow").asLong()).isEqualTo(1);
        assertThat(alerts.get("paymentPending").asLong()).isEqualTo(1);
        assertThat(alerts.get("late").asLong()).isEqualTo(1);
        assertThat(alerts.get("disputeOpen").asLong() + alerts.get("noShow").asLong()
                + alerts.get("paymentPending").asLong() + alerts.get("late").asLong())
                .isEqualTo(c.get("todo").asLong());
    }

    @Test
    void counts_negotiationExcluded() throws Exception {
        bid(BidStatus.NEGOTIATING);
        bid(BidStatus.NEGOTIATION_CLOSED);
        mockMvc.perform(counts(as(AdminRole.ADMIN)))
                .andExpect(jsonPath("$.all").value(0))
                .andExpect(jsonPath("$.active").value(0))
                .andExpect(jsonPath("$.closed").value(0));
    }

    @Test
    void counts_withoutBidView_403() throws Exception {
        mockMvc.perform(get("/admin/bids/counts").with(authentication(authWith(AdminPermission.USER_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    void counts_emptyDatabase_allZero() throws Exception {
        // Aucune ligne : les SUM valent NULL en SQL, la réponse doit porter des zéros.
        mockMvc.perform(get("/admin/bids/counts").with(authentication(as(AdminRole.ADMIN)))
                        .param("announcementId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todo").value(0))
                .andExpect(jsonPath("$.active").value(0))
                .andExpect(jsonPath("$.recent").value(0))
                .andExpect(jsonPath("$.completed").value(0))
                .andExpect(jsonPath("$.closed").value(0))
                .andExpect(jsonPath("$.all").value(0))
                .andExpect(jsonPath("$.alerts.disputeOpen").value(0))
                .andExpect(jsonPath("$.alerts.noShow").value(0))
                .andExpect(jsonPath("$.alerts.paymentPending").value(0))
                .andExpect(jsonPath("$.alerts.late").value(0));
    }

    @Test
    void counts_supportWithoutUserView_phoneNotSearched() throws Exception {
        bid(BidStatus.ACCEPTED, b -> b.setRecipientPhone("+221771234567"));
        mockMvc.perform(counts(authWith(AdminPermission.BID_VIEW), "query", "+221771234567"))
                .andExpect(jsonPath("$.all").value(0));
        mockMvc.perform(counts(as(AdminRole.ADMIN), "query", "+221771234567"))
                .andExpect(jsonPath("$.all").value(1));
    }

    @Test
    void counts_phone_followsSuffixRule_andMatchesList() throws Exception {
        bid(BidStatus.ACCEPTED, b -> b.setRecipientPhone("+33612345678"));
        bid(BidStatus.PENDING, b -> b.setRecipientPhone("+33699999999"));

        // Suffixe d'au moins huit chiffres, forme nationale comprise : un colis, comme dans la liste.
        for (String q : new String[] {"0612345678", "+33 6 12 34 56 78", "12345678"}) {
            assertThat(json(counts(as(AdminRole.ADMIN), "query", q)).get("all").asLong()).isEqualTo(1);
            assertThat(listTotal("ALL", "query", q)).isEqualTo(1);
        }
        // Quatre chiffres de tête non saisis sur un numéro de douze : rien non plus.
        bid(BidStatus.ACCEPTED, b -> b.setRecipientPhone("+221771234567"));
        assertThat(json(counts(as(AdminRole.ADMIN), "query", "71234567")).get("all").asLong()).isZero();
        assertThat(listTotal("ALL", "query", "71234567")).isZero();
        assertThat(json(counts(as(AdminRole.ADMIN), "query", "771234567")).get("all").asLong()).isEqualTo(1);
        assertThat(listTotal("ALL", "query", "771234567")).isEqualTo(1);
        // Sous-chaîne, préfixe ou moins de huit chiffres : rien, ni dans les nombres ni dans la liste.
        for (String q : new String[] {"345", "+3361", "33612345", "6123456"}) {
            assertThat(json(counts(as(AdminRole.ADMIN), "query", q)).get("all").asLong()).isZero();
            assertThat(listTotal("ALL", "query", q)).isZero();
        }
    }
}
