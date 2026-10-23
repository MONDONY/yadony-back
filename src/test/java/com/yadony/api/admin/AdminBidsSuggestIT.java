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
 * {@code GET /admin/bids/suggest} de bout en bout (MockMvc + base H2 partagée). Chaque test se restreint
 * à ses propres colis (annonce ou jeton unique) : sans filtre, les nombres incluraient ceux des autres tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminBidsSuggestIT — GET /admin/bids/suggest")
class AdminBidsSuggestIT {

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

    private AnnouncementEntity tripOf(UserEntity who, String from, String to) {
        AnnouncementEntity a = announcement();
        a.setTravelerId(who.getId());
        a.setDepartureCity(from);
        a.setArrivalCity(to);
        return announcementRepository.save(a);
    }

    private BidEntity bidOf(AnnouncementEntity a, UserEntity from, BidStatus status, Consumer<BidEntity> c) {
        return bid(status, b -> {
            b.setAnnouncementId(a.getId());
            b.setSenderId(from.getId());
            c.accept(b);
        });
    }

    // ---------------------------------------------------------------- helpers

    private static UsernamePasswordAuthenticationToken principal(AdminRole role, Iterable<AdminPermission> permissions) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (AdminPermission p : permissions) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "a@yadony.test", role, false, "uid-" + role), null, authorities);
    }

    private static UsernamePasswordAuthenticationToken as(AdminRole role) {
        return principal(role, role.permissions());
    }

    private static UsernamePasswordAuthenticationToken authWith(AdminPermission... permissions) {
        return principal(AdminRole.SUPPORT, List.of(permissions));
    }

    private UserEntity saveUser(String firstName, String lastName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
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


    private JsonNode suggest(String q) throws Exception {
        return json(get("/admin/bids/suggest").with(authentication(as(AdminRole.ADMIN))).param("q", q));
    }

    @Test
    void suggest_groupsByRole() throws Exception {
        bid(BidStatus.ACCEPTED, b -> b.setRecipientName("Fatou" + token));
        JsonNode r = suggest(token);
        assertThat(r.get("senders")).hasSize(1);
        assertThat(r.get("senders").get(0).get("id").asText()).isEqualTo(sender.getId().toString());
        assertThat(r.get("senders").get(0).get("name").asText()).isEqualTo("Aicha Send" + token);
        assertThat(r.get("travelers")).hasSize(1);
        assertThat(r.get("travelers").get(0).get("id").asText()).isEqualTo(traveler.getId().toString());
        assertThat(r.get("recipients")).hasSize(1);
        assertThat(r.get("recipients").get(0).get("name").asText()).isEqualTo("Fatou" + token);
        assertThat(r.get("trips")).isEmpty();
        assertThat(r.get("parcels")).isEmpty();
    }

    @Test
    void suggest_countsParcelsPerPerson() throws Exception {
        UserEntity other = saveUser("Zoe", "Send" + token);
        bid(BidStatus.ACCEPTED);
        bid(BidStatus.PENDING);
        bid(BidStatus.NEGOTIATING);
        bidOf(trip, other, BidStatus.PENDING, b -> { });
        JsonNode senders = suggest("Send" + token).get("senders");
        assertThat(senders).hasSize(2);
        assertThat(senders.get(0).get("id").asText()).isEqualTo(sender.getId().toString());
        assertThat(senders.get(0).get("count").asLong()).isEqualTo(2);
        assertThat(senders.get(1).get("id").asText()).isEqualTo(other.getId().toString());
        assertThat(senders.get(1).get("count").asLong()).isEqualTo(1);
        assertThat(suggest("Trav" + token).get("travelers").get(0).get("count").asLong()).isEqualTo(3);
    }

    @Test
    void suggest_ignoresAccentsAndCase() throws Exception {
        UserEntity accent = saveUser("Éloïse", "Ndiaye" + token);
        bidOf(trip, accent, BidStatus.PENDING, b -> b.setRecipientName("Ndèye" + token));
        assertThat(suggest("ELOISE ndiaye" + token).get("senders")).hasSize(1);
        assertThat(suggest("NDEYE" + token).get("recipients")).hasSize(1);
        assertThat(suggest("ndèye" + token).get("recipients").get(0).get("name").asText()).isEqualTo("Ndèye" + token);
    }

    @Test
    void suggest_limitsToFivePerGroup() throws Exception {
        for (int i = 0; i < 7; i++) {
            UserEntity s = saveUser("Mass" + i, "Lim" + token);
            bidOf(trip, s, BidStatus.PENDING, b -> b.setRecipientName("Dest" + token + UUID.randomUUID()));
        }
        JsonNode r = suggest("Lim" + token);
        assertThat(r.get("senders")).hasSize(5);
        assertThat(suggest("Dest" + token).get("recipients")).hasSize(5);
    }

    @Test
    void suggest_deletedUser_notSuggested_butParcelStillInList() throws Exception {
        UserEntity gone = saveUser("Parti", "Gone" + token);
        BidEntity b = bidOf(trip, gone, BidStatus.PENDING, x -> { });
        jdbc.update("update users set deleted_at = ? where id = ?", LocalDateTime.now(ZoneOffset.UTC), gone.getId());
        assertThat(suggest("Gone" + token).get("senders")).isEmpty();
        JsonNode list = json(get("/admin/bids").with(authentication(as(AdminRole.ADMIN)))
                .param("announcementId", trip.getId().toString()));
        assertThat(list.get("content").toString()).contains(b.getId().toString());
    }

    @Test
    void suggest_deletedTraveler_notSuggested() throws Exception {
        bid(BidStatus.PENDING);
        jdbc.update("update users set deleted_at = ? where id = ?", LocalDateTime.now(ZoneOffset.UTC), traveler.getId());
        assertThat(suggest("Trav" + token).get("travelers")).isEmpty();
    }

    @Test
    void suggest_shortQuery_returnsEmptyGroups() throws Exception {
        bid(BidStatus.PENDING);
        for (String q : new String[] {"a", " a ", "", "  "}) {
            mockMvc.perform(get("/admin/bids/suggest").with(authentication(as(AdminRole.ADMIN))).param("q", q))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.senders").isEmpty())
                    .andExpect(jsonPath("$.travelers").isEmpty())
                    .andExpect(jsonPath("$.recipients").isEmpty())
                    .andExpect(jsonPath("$.trips").isEmpty())
                    .andExpect(jsonPath("$.parcels").isEmpty());
        }
        mockMvc.perform(get("/admin/bids/suggest").with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.senders").isEmpty());
    }

    @Test
    void suggest_onlyWildcards_matchNothing() throws Exception {
        bid(BidStatus.PENDING);
        for (String q : new String[] {"%%", "__"}) {
            JsonNode r = suggest(q);
            assertThat(r.get("senders")).isEmpty();
            assertThat(r.get("parcels")).isEmpty();
            assertThat(r.get("trips")).isEmpty();
        }
    }

    @Test
    void suggest_tripTwoWords() throws Exception {
        String from = "Ville" + token;
        AnnouncementEntity t = tripOf(traveler, from, "Kidira" + token);
        bidOf(t, sender, BidStatus.PENDING, b -> { });
        bidOf(t, sender, BidStatus.ACCEPTED, b -> { });
        bidOf(t, sender, BidStatus.NEGOTIATING, b -> { });
        for (String q : new String[] {from + " Kidira" + token, "kidira" + token + " " + from.toUpperCase(), from}) {
            JsonNode trips = suggest(q).get("trips");
            assertThat(trips).hasSize(1);
            assertThat(trips.get(0).get("departureCity").asText()).isEqualTo(from);
            assertThat(trips.get(0).get("arrivalCity").asText()).isEqualTo("Kidira" + token);
            assertThat(trips.get(0).get("label").asText()).isEqualTo(from + " → Kidira" + token);
            assertThat(trips.get(0).get("count").asLong()).isEqualTo(2);
        }
        assertThat(suggest(from + " Absent" + token).get("trips")).isEmpty();
    }

    @Test
    void suggest_trackingPartial() throws Exception {
        BidEntity old = bid(BidStatus.PENDING, b -> b.setTrackingNumber("YD" + token.substring(0, 8) + "A"));
        BidEntity recent = bid(BidStatus.COMPLETED, b -> {
            b.setTrackingNumber("YD" + token.substring(0, 8) + "B");
            b.setContentCategory("Vêtements");
        });
        bid(BidStatus.NEGOTIATING, b -> b.setTrackingNumber("YD" + token.substring(0, 8) + "C"));
        jdbc.update("update bids set created_at = ? where id = ?", LocalDateTime.now(ZoneOffset.UTC).minusDays(1), old.getId());
        JsonNode parcels = suggest(token.substring(0, 8).toUpperCase()).get("parcels");
        assertThat(parcels).hasSize(2);
        assertThat(parcels.get(0).get("bidId").asText()).isEqualTo(recent.getId().toString());
        assertThat(parcels.get(0).get("trackingNumber").asText()).isEqualTo("YD" + token.substring(0, 8) + "B");
        assertThat(parcels.get(0).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(parcels.get(0).get("content").asText()).isEqualTo("Vêtements");
        assertThat(parcels.get(1).get("bidId").asText()).isEqualTo(old.getId().toString());
        assertThat(parcels.get(1).has("content")).isFalse(); // null omis (NON_NULL)
    }

    @Test
    void suggest_withoutBidView_403() throws Exception {
        mockMvc.perform(get("/admin/bids/suggest").with(authentication(authWith(AdminPermission.USER_VIEW)))
                        .param("q", "abcd"))
                .andExpect(status().isForbidden());
    }

    @Test
    void suggest_doesNotLeakPhones() throws Exception {
        BidEntity b = bid(BidStatus.PENDING, x -> {
            x.setRecipientName("Rec" + token);
            x.setRecipientPhone("+221771234567");
            x.setContentCategory("Vêtements");
        });
        Set<String> personKeys = Set.of("id", "name", "count");
        Set<String> recipientKeys = Set.of("name", "count");
        Set<String> tripKeys = Set.of("label", "departureCity", "arrivalCity", "count");
        Set<String> parcelKeys = Set.of("bidId", "trackingNumber", "status", "content");
        for (String q : new String[] {token, "+221771234567", b.getTrackingNumber()}) {
            JsonNode r = suggest(q);
            assertThat(keysOf(r.get("senders"))).isSubsetOf(personKeys);
            assertThat(keysOf(r.get("travelers"))).isSubsetOf(personKeys);
            assertThat(keysOf(r.get("recipients"))).isSubsetOf(recipientKeys);
            assertThat(keysOf(r.get("trips"))).isSubsetOf(tripKeys);
            assertThat(keysOf(r.get("parcels"))).isSubsetOf(parcelKeys);
            assertThat(r.toString()).doesNotContain("771234567");
        }
        assertThat(keysOf(suggest(token).get("recipients"))).isNotEmpty();
        assertThat(suggest("+221771234567").get("recipients")).isEmpty();
    }

    private static Set<String> keysOf(JsonNode group) {
        Set<String> keys = new HashSet<>();
        group.forEach(item -> item.fieldNames().forEachRemaining(keys::add));
        return keys;
    }

    @Test
    void suggest_tripsGroupedOnFoldedCities() throws Exception {
        String arrival = "Kaolack" + token;
        AnnouncementEntity upper = tripOf(traveler, "Paris", arrival);
        AnnouncementEntity lower = tripOf(traveler, "paris", arrival);
        bidOf(upper, sender, BidStatus.PENDING, b -> { });
        bidOf(upper, sender, BidStatus.ACCEPTED, b -> { });
        bidOf(lower, sender, BidStatus.PENDING, b -> { });
        JsonNode trips = suggest(arrival).get("trips");
        assertThat(trips).hasSize(1);
        assertThat(trips.get(0).get("count").asLong()).isEqualTo(3);
        assertThat(trips.get(0).get("departureCity").asText()).isEqualToIgnoringCase("Paris");
        assertThat(trips.get(0).get("arrivalCity").asText()).isEqualTo(arrival);
    }

    @Test
    void suggest_limitsToFive_travelersTripsAndParcels() throws Exception {
        for (int i = 0; i < 7; i++) {
            final String tracking = "YD" + token.substring(0, 8) + i; // colonne limitée à 12 caractères
            UserEntity t = saveUser("Voy" + i, "Lt" + token);
            AnnouncementEntity a = tripOf(t, "Dep" + token, "Arr" + token + i);
            bidOf(a, sender, BidStatus.PENDING, b -> b.setTrackingNumber(tracking));
        }
        assertThat(suggest("Lt" + token).get("travelers")).hasSize(5);
        assertThat(suggest("Dep" + token).get("trips")).hasSize(5);
        assertThat(suggest("YD" + token.substring(0, 8)).get("parcels")).hasSize(5);
    }

    private JsonNode json(MockHttpServletRequestBuilder req) throws Exception {
        return objectMapper.readTree(mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
