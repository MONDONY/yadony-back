package com.yadony.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.requests.entity.NegotiationThreadEntity;
import com.yadony.api.requests.entity.NegotiationThreadStatus;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /admin/payments/suggest} de bout en bout (MockMvc + base H2 partagée). Le jeton du test est fait
 * de lettres {@code g} à {@code v} (jamais un chiffre ni un pseudo hexadécimal d'un autre test) et se retrouve
 * dans les pseudos et les villes : il isole les données du test.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminPaymentSuggestIT — GET /admin/payments/suggest")
class AdminPaymentSuggestIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired PawapayOperationRepository pawapayOperationRepository;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private UserEntity sender;
    private UserEntity traveler;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        StringBuilder letters = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, 6).toCharArray()) {
            letters.append((char) ('g' + Character.digit(c, 16)));
        }
        tag = letters.toString();
        sender = user(tag + "snd", "Aïcha", "Sender");
        traveler = user(tag + "trv", "Awa", "Traveler");
        trip = announcement("Paris", "Dakar");
    }

    // ── Groupes ─────────────────────────────────────────────────────────────────

    @Test
    void qCourte_groupesVides_200() throws Exception {
        for (String q : new String[]{"a", " a ", ""}) {
            JsonNode r = suggest(q);
            assertThat(r.has("exactPaymentId")).isFalse();
            for (String g : List.of("senders", "travelers", "trips", "parcels")) {
                assertThat(r.get(g)).as(q + g).isEmpty();
            }
        }
        mockMvc.perform(get("/admin/payments/suggest").with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void personnes_nomEtNombreDePaiements_triesParNombreDecroissant() throws Exception {
        UserEntity other = user(tag + "bbb", "Aïcha", "Other");
        payment(bid(trip, sender, "YD-" + upper(tag) + "A"), "10.00");
        payment(bid(trip, sender, "YD-" + upper(tag) + "B"), "10.00");
        payment(bid(trip, other, "YD-" + upper(tag) + "C"), "10.00");

        JsonNode r = suggest("aicha " + tag);

        assertThat(r.get("senders")).hasSize(2);
        assertThat(r.get("senders").get(0).get("id").asText()).isEqualTo(sender.getId().toString());
        assertThat(r.get("senders").get(0).get("count").asLong()).isEqualTo(2);
        assertThat(r.get("senders").get(0).get("name").asText()).isEqualTo("Aïcha Sender (@" + tag + "snd)");
        assertThat(r.get("senders").get(1).get("id").asText()).isEqualTo(other.getId().toString());
        assertThat(r.get("senders").get(1).get("count").asLong()).isEqualTo(1);

        JsonNode t = suggest("awa " + tag);
        assertThat(t.get("travelers")).hasSize(1);
        assertThat(t.get("travelers").get(0).get("id").asText()).isEqualTo(traveler.getId().toString());
        assertThat(t.get("travelers").get(0).get("count").asLong()).isEqualTo(3);
        assertThat(t.get("senders")).isEmpty();
    }

    @Test
    void trajets_groupesSurLeCoupleReplie() throws Exception {
        AnnouncementEntity upperTrip = announcement("Zz" + tag, "Dak" + tag);
        AnnouncementEntity lowerTrip = announcement("zz" + tag, "dak" + tag);
        payment(bid(upperTrip, sender, null), "10.00");
        payment(bid(lowerTrip, sender, null), "10.00");

        JsonNode r = suggest("zz" + tag + " dak" + tag);

        assertThat(r.get("trips")).hasSize(1);
        JsonNode t = r.get("trips").get(0);
        assertThat(t.get("count").asLong()).isEqualTo(2);
        assertThat(t.get("label").asText()).isNotBlank();
        assertThat(t.get("departureCity").asText()).isEqualToIgnoringCase("zz" + tag);
        assertThat(t.get("arrivalCity").asText()).isEqualToIgnoringCase("dak" + tag);
    }

    @Test
    void colis_suiviStatutEtMontant() throws Exception {
        PaymentEntity p = payment(bid(trip, sender, "YD-" + upper(tag) + "8F"), "12.34");

        JsonNode r = suggest("yd-" + tag + "8f");

        assertThat(r.get("parcels")).hasSize(1);
        JsonNode parcel = r.get("parcels").get(0);
        assertThat(parcel.get("paymentId").asText()).isEqualTo(p.getId().toString());
        assertThat(parcel.get("trackingNumber").asText()).isEqualTo("YD-" + upper(tag) + "8F");
        assertThat(parcel.get("status").asText()).isEqualTo("ESCROW");
        assertThat(parcel.get("amountCents").asLong()).isEqualTo(1234);
        assertThat(parcel.get("currency").asText()).isEqualTo("EUR");
    }

    @Test
    void cinqMaximumParGroupe() throws Exception {
        for (int i = 0; i < 7; i++) {
            UserEntity s = user(tag + "s" + (char) ('a' + i), "Max", "Mx" + i);
            payment(bid(trip, s, "YD-" + upper(tag) + i), "10.00");
        }
        JsonNode r = suggest(tag);
        assertThat(r.get("senders")).hasSize(5);
        assertThat(r.get("parcels")).hasSize(5);
    }

    @Test
    void compteSupprime_absentDesPersonnes_paiementToujoursListable() throws Exception {
        PaymentEntity p = payment(bid(trip, sender, "YD-" + upper(tag) + "DL"), "10.00");
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)),
                sender.getId());

        JsonNode r = suggest(tag + "snd");

        assertThat(r.get("senders")).isEmpty();
        // Le paiement reste trouvable par son suivi.
        assertThat(suggest("yd-" + tag + "dl").get("parcels")).hasSize(1);
        assertThat(p.getId()).isNotNull();
    }

    @Test
    void checkoutAbandonne_neCompteNiNeSuggere() throws Exception {
        PaymentEntity live = payment(bid(trip, sender, "YD-" + upper(tag) + "LV"), "10.00");
        PaymentEntity dead = payment(bid(trip, sender, "YD-" + upper(tag) + "DD"), "10.00");
        jdbc.update("UPDATE payments SET status = 'PENDING', created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC).minusHours(30)), dead.getId());

        JsonNode r = suggest(tag);

        assertThat(r.get("senders").get(0).get("count").asLong()).isEqualTo(1);
        assertThat(r.get("parcels")).hasSize(1);
        assertThat(r.get("parcels").get(0).get("paymentId").asText()).isEqualTo(live.getId().toString());
    }

    @Test
    void metacaracteres_neSuggerentRien() throws Exception {
        payment(bid(trip, sender, "YD-" + upper(tag)), "10.00");
        for (String q : List.of("%%", "__", "'' ", "@@")) {
            JsonNode r = suggest(q);
            assertThat(r.get("senders")).as(q).isEmpty();
            assertThat(r.get("parcels")).as(q).isEmpty();
            assertThat(r.get("trips")).as(q).isEmpty();
        }
    }

    // ── exactPaymentId ──────────────────────────────────────────────────────────

    @Test
    void exactPaymentId_uuidDePaiementDeColisDeFilEtDOperation() throws Exception {
        BidEntity bid = bid(trip, sender, "YD-" + upper(tag) + "X1");
        PaymentEntity p = payment(bid, "10.00");
        assertThat(exact(p.getId().toString())).isEqualTo(p.getId().toString());
        assertThat(exact(p.getId().toString().toUpperCase(Locale.ROOT))).isEqualTo(p.getId().toString());
        assertThat(exact(bid.getId().toString())).isEqualTo(p.getId().toString());

        NegotiationThreadEntity thread = thread();
        PaymentEntity onThread = paymentOn(null, thread.getId(), "20.00");
        assertThat(exact(thread.getId().toString())).isEqualTo(onThread.getId().toString());

        BidEntity linked = bidOn(trip, sender, "YD-" + upper(tag) + "X2", thread.getId());
        assertThat(exact(linked.getId().toString())).isEqualTo(onThread.getId().toString());

        PawapayOperationEntity op = operation(p.getId());
        assertThat(exact(op.getId().toString())).isEqualTo(p.getId().toString());

        assertThat(exact(UUID.randomUUID().toString())).isNull();
    }

    @Test
    void exactPaymentId_referenceStripeExacte_etAmbigueAbsente() throws Exception {
        PaymentEntity p = payment(bid(trip, sender, null), "10.00");
        assertThat(exact(p.getStripePaymentIntentId())).isEqualTo(p.getId().toString());
        assertThat(exact(p.getStripePaymentIntentId().substring(0, 10))).isNull();

        PaymentEntity a = payment(bid(trip, sender, null), "11.00");
        payment(bid(trip, sender, null), "12.00");
        // Le trajet est partagé par deux paiements : identifiant ambigu, donc aucun exactPaymentId.
        // (provider_transaction_id est unique en base : il ne peut jamais être ambigu.)
        assertThat(exact(trip.getId().toString())).isNull();

        String own = "ptx2_" + tag;
        jdbc.update("UPDATE pawapay_operations SET provider_transaction_id = ? WHERE id = ?", own, operation(a.getId()).getId());
        assertThat(exact(own)).isEqualTo(a.getId().toString());
    }

    // ── Sécurité ────────────────────────────────────────────────────────────────

    @Test
    void sansPaymentView_403() throws Exception {
        mockMvc.perform(get("/admin/payments/suggest").param("q", "awa")
                        .with(authentication(authWith(AdminPermission.USER_VIEW))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aucunTelephoneDansLaReponse() throws Exception {
        PaymentEntity p = payment(bid(trip, sender, "YD-" + upper(tag) + "PH"), "10.00");
        operation(p.getId());
        for (String q : List.of(tag, "221771234567", "771234567", "+221 •••• 67", p.getId().toString())) {
            String body = mockMvc.perform(get("/admin/payments/suggest").param("q", q)
                            .with(authentication(as(AdminRole.ADMIN))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).as(q).doesNotContain("221771234567").doesNotContain("771234567")
                    .doesNotContainIgnoringCase("msisdn").doesNotContain("••••");
        }
        assertThat(suggest("221771234567").get("parcels")).isEmpty();
    }

    // ── Outils ──────────────────────────────────────────────────────────────────

    private JsonNode suggest(String q) throws Exception {
        String body = mockMvc.perform(get("/admin/payments/suggest").param("q", q)
                        .with(authentication(as(AdminRole.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String exact(String q) throws Exception {
        JsonNode r = suggest(q);
        return r.has("exactPaymentId") ? r.get("exactPaymentId").asText() : null;
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

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

    private BidEntity bid(AnnouncementEntity announcement, UserEntity from, String tracking) {
        return bidOn(announcement, from, tracking, null);
    }

    private BidEntity bidOn(AnnouncementEntity announcement, UserEntity from, String tracking, UUID linkedThreadId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(from.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setTrackingNumber(tracking);
        bid.setLinkedNegotiationThreadId(linkedThreadId);
        return bidRepository.save(bid);
    }

    private PaymentEntity payment(BidEntity bid, String amount) {
        return paymentOn(bid.getId(), null, amount);
    }

    private PaymentEntity paymentOn(UUID bidId, UUID threadId, String amount) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency("EUR");
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal(amount).divide(BigDecimal.TEN));
        p.setStatus(PaymentStatus.ESCROW);
        return paymentRepository.save(p);
    }

    private PawapayOperationEntity operation(UUID paymentId) {
        return pawapayOperationRepository.save(new PawapayOperationEntity(UUID.randomUUID(),
                PawapayOperationKind.PAYOUT, paymentId, null, new BigDecimal("6600.00"), "XOF", "ORANGE_SEN", "SN",
                "221771234567"));
    }

    private UserEntity user(String username, String firstName, String lastName) {
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

    private AnnouncementEntity announcement(String departure, String arrival) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity(departure);
        a.setArrivalCity(arrival);
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel(departure);
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel(arrival);
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private NegotiationThreadEntity thread() {
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
