package com.yadony.api.admin;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recherche multi-critères de la liste des paiements ({@link AdminPaymentSql#SEARCH_PREDICATE}) : jetons
 * repliés (suivi, parties, villes), montant, identifiants exacts, références. Le jeton du test est fait de
 * lettres {@code g} à {@code v}, absentes des pseudos hexadécimaux des autres tests et de tout chiffre :
 * ajouté à la saisie, il isole les données du test sur la base H2 partagée.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminPaymentSearchIT {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0);

    @Autowired AdminPaymentInsights insights;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PackageRequestRepository packageRequestRepository;
    @Autowired NegotiationThreadRepository negotiationThreadRepository;
    @Autowired ChargebackRepository chargebackRepository;
    @Autowired PawapayOperationRepository pawapayOperationRepository;
    @Autowired JdbcTemplate jdbc;

    /** Six lettres de g à v : jamais un chiffre, jamais un pseudo hexadécimal d'un autre test. */
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
        sender = persistUser(tag + "snd", "Aïcha", "N'Diaye");
        traveler = persistUser(tag + "trv", "Awa", "Diallo");
        trip = persistAnnouncement("Paris", "Dakar");
    }

    @Test
    void jetons_suiviPartiesEtVilles_dansNimporteQuelOrdre() {
        PaymentEntity p = payment(bid(trip, "YD-" + upper(tag) + "2Q"), "33.00");

        assertThat(search("Paris Dakar " + tag)).containsExactly(p.getId());
        assertThat(search("dakar paris " + tag)).containsExactly(p.getId());
        assertThat(search("Awa Diallo " + tag)).containsExactly(p.getId());
        assertThat(search("diallo awa " + tag)).containsExactly(p.getId());
        assertThat(search("Lyon " + tag)).isEmpty();
        // Un jeton ne chevauche pas deux champs : « dakar » + pseudo n'est pas un seul mot.
        assertThat(search("dakar" + tag)).isEmpty();
    }

    @Test
    void accentsEtApostrophes_replies_majusculesAccentueesComprises() {
        AnnouncementEntity thies = persistAnnouncement("Paris", "THIÈS");
        PaymentEntity p = payment(bid(thies, null), "33.00");

        assertThat(search("aicha " + tag)).containsExactly(p.getId());
        assertThat(search("Aïcha " + tag)).containsExactly(p.getId());
        assertThat(search("ndiaye " + tag)).containsExactly(p.getId());
        assertThat(search("N'Diaye " + tag)).containsExactly(p.getId());
        assertThat(search("N’DIAYE " + tag)).containsExactly(p.getId());
        assertThat(search("thies " + tag)).containsExactly(p.getId());
        assertThat(search("Thiès " + tag)).containsExactly(p.getId());
    }

    @Test
    void suiviPartiel_etPrefixeHistoriqueDON() {
        PaymentEntity yd = payment(bid(trip, "YD-" + upper(tag) + "2Q"), "33.00");
        PaymentEntity don = payment(bid(trip, "DON-" + upper(tag) + "A"), "34.00");

        assertThat(search(tag.substring(1, 5))).contains(yd.getId(), don.getId());
        assertThat(search(tag.substring(2, 6) + "2q")).containsExactly(yd.getId());
        assertThat(search("don-" + tag)).containsExactly(don.getId());
        assertThat(search("DON-" + upper(tag) + "A")).containsExactly(don.getId());
    }

    @Test
    void montant_enOuAvecLesJetons() {
        PaymentEntity exact = payment(bid(trip, null), "120.00");
        PaymentEntity bigger = payment(bid(trip, null), "1200.00");
        PaymentEntity tracked = payment(bid(trip, "YD-120" + upper(tag)), "33.00");

        for (String q : List.of("120", "120,00", "120.00 €", "120 €")) {
            assertThat(search(q)).as(q).contains(exact.getId()).doesNotContain(bigger.getId());
        }
        assertThat(search("120")).contains(tracked.getId());
        assertThat(search("1200")).contains(bigger.getId()).doesNotContain(exact.getId());
        // Le montant n'avale pas les jetons : « 120 <tag> » ne trouve que le colis dont le suivi contient 120.
        assertThat(search("120 " + tag)).containsExactly(tracked.getId());
    }

    @Test
    void identifiantsExacts_paiementColisFilAnnonceOperationEtLitigeBancaire() {
        BidEntity direct = bid(trip, null);
        PaymentEntity p = payment(direct, "33.00");
        NegotiationThreadEntity thread = persistThread();
        AnnouncementEntity otherTrip = persistAnnouncement("Marseille", "Bamako");
        BidEntity linked = bidOn(otherTrip, null, thread.getId());
        PaymentEntity nego = paymentOn(null, thread.getId(), "44.00");
        PawapayOperationEntity op = operation(p.getId());
        ChargebackEntity chargeback = chargeback(p.getId(), null);
        ChargebackEntity byBid = chargeback(null, linked.getId());

        assertThat(search(p.getId().toString())).containsExactly(p.getId());
        assertThat(search(p.getId().toString().toUpperCase(Locale.ROOT))).containsExactly(p.getId());
        assertThat(search(direct.getId().toString())).containsExactly(p.getId());
        assertThat(search(linked.getId().toString())).containsExactly(nego.getId());
        assertThat(search(thread.getId().toString())).containsExactly(nego.getId());
        assertThat(search(trip.getId().toString())).containsExactly(p.getId());
        assertThat(search(otherTrip.getId().toString())).containsExactly(nego.getId());
        assertThat(search(op.getId().toString())).containsExactly(p.getId());
        assertThat(search(chargeback.getId().toString())).containsExactly(p.getId());
        assertThat(search(byBid.getId().toString())).containsExactly(nego.getId());
        assertThat(search(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void references_stripeEtFournisseurPawapay_exactes() {
        PaymentEntity p = payment(bid(trip, null), "33.00");
        String charge = "ch_" + UUID.randomUUID().toString().replace("-", "");
        p.setStripeChargeId(charge);
        paymentRepository.save(p);
        PawapayOperationEntity op = operation(p.getId());
        String provider = "MP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        jdbc.update("UPDATE pawapay_operations SET provider_transaction_id = ? WHERE id = ?", provider, op.getId());

        assertThat(search(p.getStripePaymentIntentId())).containsExactly(p.getId());
        assertThat(search(charge)).containsExactly(p.getId());
        assertThat(search(provider)).containsExactly(p.getId());
        assertThat(search(provider.substring(0, 8))).doesNotContain(p.getId());
    }

    @Test
    void saisieSansJeton_neTrouveRien_etCinqMotsNeGardentQueLesQuatrePremiers() {
        PaymentEntity p = payment(bid(trip, null), "33.00");

        assertThat(search("%")).isEmpty();
        assertThat(search("_")).isEmpty();
        assertThat(search("'")).isEmpty();
        assertThat(search("% _")).isEmpty();
        assertThat(search(tag + " aicha awa diallo inconnuzz")).containsExactly(p.getId());
        assertThat(search("inconnuzz " + tag + " aicha awa diallo")).isEmpty();
    }

    @Test
    void compteSupprime_paiementToujoursTrouvableParSuivi() {
        PaymentEntity p = payment(bid(trip, "YD-" + upper(tag) + "DL"), "33.00");
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", Timestamp.valueOf(NOW), sender.getId());

        assertThat(search("yd-" + tag + "dl")).containsExactly(p.getId());
    }

    @Test
    void telephoneDUneOperation_jamaisCherche() {
        PaymentEntity p = payment(bid(trip, null), "33.00");
        operation(p.getId());

        for (String q : List.of("221771234567", "+221771234567", "771234567", "67", "+221 •••• 67", "•••• 67")) {
            assertThat(search(q)).as(q).doesNotContain(p.getId());
        }
    }

    @Test
    void listeTotauxEtExport_partagentLaRecherche() {
        PaymentEntity a = payment(bid(trip, null), "33.00");
        PaymentEntity b = payment(bid(trip, null), "44.00");
        AdminPaymentFilter filter = filter("paris " + tag);

        assertThat(search("paris " + tag)).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(insights.search(filter, NOW, PageRequest.of(0, 1)).getTotalElements()).isEqualTo(2);
        assertThat(insights.exportRows(filter, NOW)).extracting(PaymentEntity::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(insights.totals(filter, NOW)).singleElement()
                .satisfies(t -> assertThat(t.count()).isEqualTo(2));
    }

    // ── Outils ──────────────────────────────────────────────────────────────────

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    private static AdminPaymentFilter filter(String q) {
        return AdminPaymentFilter.of(null, null, null, null, null, null, q, null);
    }

    private List<UUID> search(String q) {
        return insights.search(filter(q), NOW, PageRequest.of(0, 1000)).getContent().stream()
                .map(PaymentEntity::getId).toList();
    }

    private BidEntity bid(AnnouncementEntity announcement, String tracking) {
        return bidOn(announcement, tracking, null);
    }

    private BidEntity bidOn(AnnouncementEntity announcement, String tracking, UUID linkedThreadId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
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
        p = paymentRepository.save(p);
        jdbc.update("UPDATE payments SET created_at = ? WHERE id = ?", Timestamp.valueOf(NOW.minusHours(1)), p.getId());
        return p;
    }

    private PawapayOperationEntity operation(UUID paymentId) {
        return pawapayOperationRepository.save(new PawapayOperationEntity(UUID.randomUUID(),
                PawapayOperationKind.PAYOUT, paymentId, null, new BigDecimal("6600.00"), "XOF", "ORANGE_SEN", "SN",
                "221771234567"));
    }

    private ChargebackEntity chargeback(UUID paymentId, UUID bidId) {
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

    private AnnouncementEntity persistAnnouncement(String departure, String arrival) {
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
