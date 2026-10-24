package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminChargebackCountsResponse;
import com.yadony.api.admin.dto.AdminChargebackResponse;
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
import com.yadony.api.payments.cash.PaymentMethod;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Liste des litiges bancaires ({@link AdminFinanceListings#chargebacks}) : lignes enrichies (paiement, colis,
 * trajet, expéditeur, voyageur, issue), vues, recherche repliée, nombres. Chaque test se restreint à ses
 * données par son étiquette {@code tag} (lettres seules) : la base H2 partagée ne mélange pas les jeux.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminChargebackListingIT {

    @Autowired AdminFinanceListings listings;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ChargebackRepository chargebackRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private int minutes;
    private UserEntity sender;
    private UserEntity traveler;
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        tag = "cb" + letters(10);
        minutes = 0;
        sender = persistUser("Aïcha", "Sender" + tag, "s" + tag);
        traveler = persistUser(tag, "Diallo", "t" + tag);
        trip = persistAnnouncement(traveler, "Paris", "Dakar");
    }

    @Test
    void ligne_paiementDuLitige_colisTrajetPersonnesEtIssue() {
        BidEntity bid = persistBid(trip, "YC" + tag.substring(2, 10));
        PaymentEntity payment = persistPayment(bid.getId());
        ChargebackEntity c = chargeback(payment.getId(), bid.getId(), ChargebackStatus.LOST);
        c.setOutcome("lost");
        c.setResolvedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        c.setReason("fraudulent");
        c.setCurrency("eur");
        c.setAmount(4200);
        chargebackRepository.save(c);

        AdminChargebackResponse r = only(null, tag);

        assertThat(r.id()).isEqualTo(c.getId());
        assertThat(r.bidId()).isEqualTo(bid.getId());
        assertThat(r.paymentId()).isEqualTo(payment.getId());
        assertThat(r.trackingNumber()).isEqualTo("YC" + tag.substring(2, 10));
        assertThat(r.corridor()).isEqualTo("Paris → Dakar");
        assertThat(r.senderId()).isEqualTo(sender.getId());
        assertThat(r.senderName()).isEqualTo("Aïcha Sender" + tag + " (@s" + tag + ")");
        assertThat(r.travelerId()).isEqualTo(traveler.getId());
        assertThat(r.travelerName()).isEqualTo(tag + " Diallo (@t" + tag + ")");
        assertThat(r.status()).isEqualTo("LOST");
        assertThat(r.outcome()).isEqualTo("lost");
        assertThat(r.resolvedAt()).isNotNull();
        assertThat(r.openedAt()).isNotNull();
        assertThat(r.amountCents()).isEqualTo(4200);
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.reason()).isEqualTo("fraudulent");
    }

    @Test
    void paiementDuColis_quandLeLitigeNePorteQueLeColis() {
        BidEntity bid = persistBid(trip, null);
        PaymentEntity payment = persistPayment(bid.getId());
        chargeback(null, bid.getId(), ChargebackStatus.OPEN);

        AdminChargebackResponse r = only(null, tag);

        assertThat(r.paymentId()).isEqualTo(payment.getId());
        assertThat(r.senderId()).isEqualTo(sender.getId());
        assertThat(r.travelerId()).isEqualTo(traveler.getId());
    }

    @Test
    void colisSansPaiement_paiementAbsent_etLitigeSansColis_contexteAbsent() throws Exception {
        BidEntity bid = persistBid(trip, null);
        chargeback(null, bid.getId(), ChargebackStatus.OPEN);
        AdminChargebackResponse r = only(null, tag);
        assertThat(r.paymentId()).isNull();
        assertThat(r.corridor()).isEqualTo("Paris → Dakar");

        // Litige sans colis ni paiement : seulement les champs propres, aucun champ nul dans le JSON.
        ChargebackEntity orphan = chargeback(null, null, ChargebackStatus.WON);
        mockMvc.perform(get("/admin/chargebacks").param("view", "WON").param("size", "100")
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + orphan.getId() + "')].paymentId").isEmpty())
                .andExpect(jsonPath("$.content[?(@.id=='" + orphan.getId() + "')].trackingNumber").isEmpty());
    }

    @Test
    void paiementDeNegociation_colisLieApres_resoluParLeFil_sansDoublon() {
        NegotiationThreadEntity thread = persistThread();
        PaymentEntity payment = persistThreadPayment(thread.getId());
        ChargebackEntity c = chargeback(payment.getId(), null, ChargebackStatus.OPEN);
        // Tant que le fil n'a pas de colis : listé, sans champ de colis.
        assertThat(listings.chargebacks(null, null, PageRequest.of(0, 100)).getContent())
                .filteredOn(r -> r.id().equals(c.getId())).singleElement().satisfies(r -> {
                    assertThat(r.paymentId()).isEqualTo(payment.getId());
                    assertThat(r.trackingNumber()).isNull();
                    assertThat(r.corridor()).isNull();
                    assertThat(r.senderId()).isNull();
                    assertThat(r.travelerId()).isNull();
                });

        // Le colis est créé ensuite et lié au fil.
        BidEntity bid = persistBid(trip, "YL" + tag.substring(2, 10));
        bid.setLinkedNegotiationThreadId(thread.getId());
        bidRepository.save(bid);

        AdminChargebackResponse r = only(null, tag);
        assertThat(r.id()).isEqualTo(c.getId());
        assertThat(r.bidId()).isEqualTo(bid.getId());
        assertThat(r.paymentId()).isEqualTo(payment.getId());
        assertThat(r.trackingNumber()).isEqualTo("YL" + tag.substring(2, 10));
        assertThat(r.corridor()).isEqualTo("Paris → Dakar");
        assertThat(r.senderName()).isEqualTo("Aïcha Sender" + tag + " (@s" + tag + ")");
        assertThat(r.travelerName()).isEqualTo(tag + " Diallo (@t" + tag + ")");
        // Trouvé par le suivi et par une ville du trajet, une seule fois dans la liste et les nombres.
        assertThat(ids("yl" + tag.substring(2, 10))).containsExactly(c.getId());
        assertThat(ids("dakar " + tag)).containsExactly(c.getId());
        assertThat(listings.chargebackCounts(tag)).isEqualTo(new AdminChargebackCountsResponse(1, 0, 0, 1));
        assertThat(listings.chargebackCounts("yl" + tag.substring(2, 10)).all()).isEqualTo(1);
    }

    @Test
    void vues_etNombresCoherents() {
        persistChargebackWithBid(ChargebackStatus.OPEN);
        persistChargebackWithBid(ChargebackStatus.OPEN);
        persistChargebackWithBid(ChargebackStatus.WON);
        persistChargebackWithBid(ChargebackStatus.LOST);

        assertThat(rows(AdminChargebackView.OPEN, tag)).hasSize(2)
                .allMatch(r -> r.status().equals("OPEN"));
        assertThat(rows(AdminChargebackView.WON, tag)).hasSize(1);
        assertThat(rows(AdminChargebackView.LOST, tag)).hasSize(1);
        assertThat(rows(AdminChargebackView.ALL, tag)).hasSize(4);
        assertThat(rows(null, tag)).hasSize(4);

        AdminChargebackCountsResponse counts = listings.chargebackCounts(tag);
        assertThat(counts).isEqualTo(new AdminChargebackCountsResponse(2, 1, 1, 4));
        assertThat(counts.open()).isEqualTo(rows(AdminChargebackView.OPEN, tag).size());
        assertThat(counts.all()).isEqualTo(rows(AdminChargebackView.ALL, tag).size());
    }

    @Test
    void litigeSupprime_exclu() {
        ChargebackEntity c = persistChargebackWithBid(ChargebackStatus.OPEN);
        jdbc.update("UPDATE chargebacks SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", c.getId());

        assertThat(rows(null, tag)).isEmpty();
        assertThat(listings.chargebackCounts(tag).all()).isZero();
    }

    @Test
    void ordre_ouvertureDecroissante_etPagination() {
        ChargebackEntity first = persistChargebackWithBid(ChargebackStatus.OPEN);
        ChargebackEntity second = persistChargebackWithBid(ChargebackStatus.OPEN);
        ChargebackEntity third = persistChargebackWithBid(ChargebackStatus.OPEN);

        assertThat(rows(null, tag)).extracting(AdminChargebackResponse::id)
                .containsExactly(third.getId(), second.getId(), first.getId());
        var page = listings.chargebacks(null, tag, PageRequest.of(1, 2));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(AdminChargebackResponse::id).containsExactly(first.getId());
    }

    @Test
    void q_suiviNomsTrajet_accentsEtJetons() {
        BidEntity dakar = persistBid(trip, "YC" + tag.substring(2, 10));
        ChargebackEntity cDakar = chargeback(null, dakar.getId(), ChargebackStatus.OPEN);
        AnnouncementEntity abidjan = persistAnnouncement(traveler, "Marseille", "Abidjan");
        ChargebackEntity cAbidjan = chargeback(null, persistBid(abidjan, "YD" + tag.substring(2, 10)).getId(),
                ChargebackStatus.OPEN);
        UserEntity otherTraveler = persistUser("Bintou" + tag, "Koné", "k" + tag);
        ChargebackEntity cElsewhere = chargeback(null,
                persistBid(persistAnnouncement(otherTraveler, "Lyon", "Bamako"), null).getId(), ChargebackStatus.OPEN);

        assertThat(ids("YC" + tag.substring(2, 10).toLowerCase())).containsExactly(cDakar.getId());
        assertThat(ids("diallo " + tag)).containsExactlyInAnyOrder(cDakar.getId(), cAbidjan.getId());
        assertThat(ids("@t" + tag)).containsExactlyInAnyOrder(cDakar.getId(), cAbidjan.getId());
        assertThat(ids("aicha sender" + tag)).containsExactlyInAnyOrder(cDakar.getId(), cAbidjan.getId(),
                cElsewhere.getId());
        assertThat(ids("marseille " + tag)).containsExactly(cAbidjan.getId());
        assertThat(ids("bintou" + tag + " KONE")).containsExactly(cElsewhere.getId());
        assertThat(ids("zzz" + tag)).isEmpty();
        for (String q : List.of("%", "_", "'", "@")) {
            assertThat(listings.chargebacks(null, q, PageRequest.of(0, 50)).getTotalElements()).as("q=%s", q).isZero();
            assertThat(listings.chargebackCounts(q).all()).as("counts q=%s", q).isZero();
        }
        // Les nombres suivent q, pas la vue.
        assertThat(listings.chargebackCounts(tag).all()).isEqualTo(3);
        assertThat(listings.chargebackCounts("bintou" + tag).all()).isEqualTo(1);
    }

    @Test
    void comptesSupprimes_idsConserves_nomsAbsents() {
        persistChargebackWithBid(ChargebackStatus.OPEN);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", traveler.getId());

        AdminChargebackResponse r = only(null, sender.getUsername());
        assertThat(r.travelerId()).isEqualTo(traveler.getId());
        assertThat(r.travelerName()).isNull();
        assertThat(r.senderId()).isEqualTo(sender.getId());
    }

    @Test
    void http_vueRechercheEtNombres() throws Exception {
        persistChargebackWithBid(ChargebackStatus.OPEN);
        persistChargebackWithBid(ChargebackStatus.WON);

        mockMvc.perform(get("/admin/chargebacks").param("view", "OPEN").param("q", tag)
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].corridor").value("Paris → Dakar"))
                .andExpect(jsonPath("$.content[0].outcome").doesNotExist());
        mockMvc.perform(get("/admin/chargebacks").param("q", tag).with(authentication(admin())))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/admin/chargebacks/counts").param("q", tag).param("view", "OPEN")
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(1))
                .andExpect(jsonPath("$.won").value(1))
                .andExpect(jsonPath("$.lost").value(0))
                .andExpect(jsonPath("$.all").value(2));
        mockMvc.perform(get("/admin/chargebacks").param("view", "BIZARRE").with(authentication(admin())))
                .andExpect(status().isBadRequest());
        // Taille bornée : une URL bricolée ne ramène pas toute la table.
        mockMvc.perform(get("/admin/chargebacks").param("size", "100000").with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void http_accesRefuseSansPaymentView() throws Exception {
        var noPermission = new UsernamePasswordAuthenticationToken("x", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mockMvc.perform(get("/admin/chargebacks").with(authentication(noPermission)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/chargebacks/counts").with(authentication(noPermission)))
                .andExpect(status().isForbidden());
    }

    // ── Aides ───────────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken admin() {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT,
                false, "uid-support-cb");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_VIEW")));
    }

    private List<AdminChargebackResponse> rows(AdminChargebackView view, String q) {
        return listings.chargebacks(view, q, PageRequest.of(0, 100)).getContent();
    }

    private List<UUID> ids(String q) {
        return rows(null, q).stream().map(AdminChargebackResponse::id).toList();
    }

    private AdminChargebackResponse only(AdminChargebackView view, String q) {
        List<AdminChargebackResponse> rows = rows(view, q);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private ChargebackEntity persistChargebackWithBid(ChargebackStatus status) {
        BidEntity bid = persistBid(trip, null);
        return chargeback(persistPayment(bid.getId()).getId(), bid.getId(), status);
    }

    private ChargebackEntity chargeback(UUID paymentId, UUID bidId, ChargebackStatus status) {
        ChargebackEntity c = new ChargebackEntity();
        c.setStripeDisputeId("dp_" + UUID.randomUUID().toString().replace("-", ""));
        c.setPaymentId(paymentId);
        c.setBidId(bidId);
        c.setAmount(1000);
        c.setCurrency("EUR");
        c.setStatus(status);
        c.setOpenedAt(Instant.parse("2026-10-20T08:00:00Z").plus(minutes++, ChronoUnit.MINUTES));
        return chargebackRepository.save(c);
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
        t.setCurrency("EUR");
        t.setCurrentPriceEur(new BigDecimal("200.00"));
        t.setRoundsCount((short) 1);
        t.setLastActivityAt(LocalDateTime.now());
        return negotiationThreadRepository.save(t);
    }

    private PaymentEntity persistThreadPayment(UUID threadId) {
        return persistPayment(null, threadId);
    }

    private PaymentEntity persistPayment(UUID bidId) {
        return persistPayment(bidId, null);
    }

    private PaymentEntity persistPayment(UUID bidId, UUID threadId) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", ""));
        p.setCurrency("EUR");
        p.setAmount(new BigDecimal("10.00"));
        p.setCommissionAmount(new BigDecimal("1.00"));
        p.setStatus(PaymentStatus.RELEASED);
        return paymentRepository.save(p);
    }

    private BidEntity persistBid(AnnouncementEntity announcement, String tracking) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("2.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setTrackingNumber(tracking);
        return bidRepository.save(bid);
    }

    private UserEntity persistUser(String firstName, String lastName, String username) {
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

    private AnnouncementEntity persistAnnouncement(UserEntity owner, String departure, String arrival) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(owner.getId());
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

    /** Lettres seules, minuscules. */
    private static String letters(int length) {
        StringBuilder out = new StringBuilder(length);
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, length).toCharArray()) {
            out.append((char) ('g' + Character.digit(c, 16)));
        }
        return out.toString();
    }
}
