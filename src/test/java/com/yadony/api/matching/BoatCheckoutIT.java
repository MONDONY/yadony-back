package com.yadony.api.matching;

import com.stripe.model.PaymentIntent;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.matching.dto.BidCheckoutRequest;
import com.yadony.api.matching.dto.BidCheckoutResponse;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.matching.dto.BidQuoteRequest;
import com.yadony.api.matching.dto.BidQuoteResponse;
import com.yadony.api.matching.dto.BidRequest;
import com.yadony.api.matching.dto.BidResponse;
import com.yadony.api.payments.StripeGateway;
import com.yadony.api.payments.cash.CashCommissionService;
import com.yadony.api.payments.cash.CommissionSource;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Réservation d'un trajet bateau vendu au m³ (BB2) sur PostgreSQL embarqué, toutes migrations Flyway et
 * {@code ddl-auto=validate} : devis, offre ferme, paiement carte (escrow réel du service, passerelle Stripe
 * bouchonnée), proposition de prix, acceptations (carte, espèces, fil payé) et rendu du volume, avec les
 * CHECK de V382 ({@code chk_announcements_volume}, {@code chk_bids_volume}) appliqués pour de vrai.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Réservation d'un bateau au m³ (Postgres, prix et capacité)")
class BoatCheckoutIT {

    private static EmbeddedPostgres postgres;

    @Autowired BidService bidService;
    @Autowired BidCheckoutService bidCheckoutService;
    @Autowired BidNegotiationService bidNegotiationService;
    @Autowired ThreadAcceptedBidListener threadAcceptedBidListener;
    @Autowired CashCommissionService cashCommissionService;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired WalletAccountRepository walletAccountRepository;
    @Autowired PlatformSettingsService platformSettings;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @MockitoBean StripeGateway stripeGateway;

    private UUID travelerId;
    private String travelerUid;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @BeforeEach
    void setUp() throws Exception {
        travelerId = UUID.randomUUID();
        travelerUid = "uid-boat-m3-" + travelerId;
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status, gp_pro, is_pro_account,
                                   stripe_account_id, stripe_account_status)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED', true, false, ?, 'ONBOARDING_COMPLETE')
                """, travelerId, travelerUid, ("t" + travelerId.toString().replace("-", "")).substring(0, 20),
                "acct_" + travelerId.toString().replace("-", "").substring(0, 16));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'TRAVELER')", travelerId);
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "true"), UUID.randomUUID());
        when(stripeGateway.createPaymentIntent(any())).thenAnswer(inv -> {
            PaymentIntent pi = new PaymentIntent();
            pi.setId("pi_boat_m3_" + UUID.randomUUID());
            pi.setClientSecret("secret_boat_m3");
            return pi;
        });
    }

    @AfterEach
    void closeBoat() {
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "false"), UUID.randomUUID());
    }

    // ── Décor ────────────────────────────────────────────────────────────────

    private record Sender(UUID id, String uid) {}

    private Sender sender() {
        UUID id = UUID.randomUUID();
        String uid = "uid-boat-m3-sender-" + id;
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status, stripe_customer_id)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED', 'cus_boat_m3')
                """, id, uid, ("s" + id.toString().replace("-", "")).substring(0, 20));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'SENDER')", id);
        return new Sender(id, uid);
    }

    /** Trajet bateau vendu au m³ (aucun kilo, V382 chk_announcements_kg_or_m3), 600 € / m³ net. */
    private AnnouncementEntity boatPerM3(String currency, String pricePerM3, String availableVolumeM3) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Marseille");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setArrivalDate(LocalDate.now().plusDays(30));
        a.setArrivalDateMax(LocalDate.now().plusDays(40));
        a.setTransportMode(TransportMode.BOAT);
        a.setPickupAddressLabel("Port de Marseille");
        a.setPickupLat(new BigDecimal("43.300000"));
        a.setPickupLng(new BigDecimal("5.370000"));
        a.setDeliveryAddressLabel("Port de Dakar");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.440000"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(null);
        a.setTotalKg(null);
        a.setPricePerKg(null);
        a.setPricePerKgEur(null);
        a.setPricePerM3(new BigDecimal(pricePerM3));
        a.setVolumeCapacityM3(new BigDecimal("2.500"));
        a.setAvailableVolumeM3(new BigDecimal(availableVolumeM3));
        a.setMaxParcelWeightKg(new BigDecimal("30.00"));
        a.setCurrency(currency);
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
        return announcementRepository.saveAndFlush(a);
    }

    private static BidRequest offer(String weightKg, String paymentMethod, Integer l, Integer w, Integer h) {
        return new BidRequest(weightKg == null ? null : new BigDecimal(weightKg), "Vêtements", "OTHER", "Awa",
                "+221771234567", true, paymentMethod, null, null, null, null, null, null, l, w, h);
    }

    private static BidCheckoutRequest card(UUID tripId, String weightKg, Integer l, Integer w, Integer h) {
        return new BidCheckoutRequest(tripId, weightKg == null ? null : new BigDecimal(weightKg), "Vêtements",
                "OTHER", "Awa", "+221771234567", true, null, null, null, null, l, w, h);
    }

    private Map<String, Object> tripRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT status, available_kg, available_volume_m3 FROM announcements WHERE id = ?", id);
    }

    private BigDecimal availableVolume(UUID tripId) {
        return (BigDecimal) tripRow(tripId).get("available_volume_m3");
    }

    private long bids(UUID tripId) {
        return jdbc.queryForObject("SELECT count(*) FROM bids WHERE announcement_id = ?", Long.class, tripId);
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                      HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getErrorCode()).isEqualTo(code);
        });
    }

    /** Colis carte déjà payé (séquestre ouvert), prêt à être accepté par le voyageur. */
    private UUID escrowedParcel(AnnouncementEntity trip, String volumeM3) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(trip.getId());
        b.setSenderId(sender().id());
        b.setVolumeM3(new BigDecimal(volumeM3));
        b.setDescription("Vêtements");
        b.setContentCategory("CLOTHING");
        b.setRecipientName("Aminata");
        b.setRecipientPhone("+221701234567");
        b.setCurrency(trip.getCurrency());
        b.setPaymentMethod(PaymentMethod.STRIPE);
        b.setStatus(BidStatus.PAYMENT_ESCROWED);
        return bidRepository.saveAndFlush(b).getId();
    }

    // ── Devis et prix ────────────────────────────────────────────────────────

    @Test
    @DisplayName("devis : 40 × 40 × 60 cm = 0,096 m³ × 600 € = 57,60 € net, total frais inclus, poids non tarifé")
    void quote_boatPerM3_priceIsVolumeTimesRate() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();

        BidQuoteResponse quote = bidService.quote(s.uid(),
                new BidQuoteRequest(trip.getId(), new BigDecimal("12"), null, null, 40, 40, 60));

        assertThat(quote.netEur()).isEqualByComparingTo("57.60");
        assertThat(quote.volumeNetEur()).isEqualByComparingTo("57.60");
        assertThat(quote.kgNetEur()).isEqualByComparingTo("0");
        assertThat(quote.totalEur()).isEqualByComparingTo(quote.netEur().add(quote.commissionEur()));
        assertThat(quote.totalEur()).isGreaterThan(quote.netEur());
        assertThat(quote.currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("devis en franc CFA : net arrondi au franc (0,096 × 123 457 = 11 851,872 → 11 852)")
    void quote_boatXof_roundsToMinorUnit() {
        AnnouncementEntity trip = boatPerM3("XOF", "123457", "2.500");
        Sender s = sender();

        BidQuoteResponse quote = bidService.quote(s.uid(),
                new BidQuoteRequest(trip.getId(), null, null, null, 40, 40, 60));

        assertThat(quote.netEur()).isEqualByComparingTo("11852");
        assertThat(quote.currency()).isEqualTo("XOF");
    }

    @Test
    @DisplayName("devis, offre et paiement carte sans dimensions → 422 boat-dimensions-required, aucun colis")
    void boatWithoutDimensions_422() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();

        assertProblem(() -> bidService.quote(s.uid(),
                new BidQuoteRequest(trip.getId(), new BigDecimal("5"), null, null)),
                HttpStatus.UNPROCESSABLE_ENTITY, "boat-dimensions-required");
        assertProblem(() -> bidService.createBid(trip.getId(), s.uid(), offer("5", "CASH", null, null, null),
                new MockHttpServletRequest()), HttpStatus.UNPROCESSABLE_ENTITY, "boat-dimensions-required");
        assertProblem(() -> bidCheckoutService.checkout(s.uid(), card(trip.getId(), "5", null, null, null),
                new MockHttpServletRequest()), HttpStatus.UNPROCESSABLE_ENTITY, "boat-dimensions-required");

        assertThat(bids(trip.getId())).isZero();
    }

    @Test
    @DisplayName("volume au-delà du restant → 422 insufficient-volume partout, aucun colis ni paiement")
    void boatVolumeTooLarge_422() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "0.095");
        trip.setNegotiable(true);
        announcementRepository.saveAndFlush(trip);
        Sender s = sender();

        assertProblem(() -> bidService.quote(s.uid(),
                new BidQuoteRequest(trip.getId(), null, null, null, 40, 40, 60)),
                HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-volume");
        assertProblem(() -> bidService.createBid(trip.getId(), s.uid(), offer(null, "CASH", 40, 40, 60),
                new MockHttpServletRequest()), HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-volume");
        assertProblem(() -> bidCheckoutService.checkout(s.uid(), card(trip.getId(), null, 40, 40, 60),
                new MockHttpServletRequest()), HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-volume");
        assertProblem(() -> bidNegotiationService.propose(trip.getId(), s.uid(),
                new BidNegotiationStartRequest(null, "Vêtements", "OTHER", "Awa", "+221771234567", true,
                        null, null, null, null, new BigDecimal("50"), null, null, null, 40, 40, 60),
                new MockHttpServletRequest()), HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-volume");

        assertThat(bids(trip.getId())).isZero();
    }

    @Test
    @DisplayName("poids au-delà du poids max par colis du bateau → 422 parcel-too-heavy (règle du lot 2)")
    void boatParcelTooHeavy_422() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();

        assertProblem(() -> bidService.createBid(trip.getId(), s.uid(), offer("31", "CASH", 40, 40, 60),
                new MockHttpServletRequest()), HttpStatus.UNPROCESSABLE_ENTITY, "parcel-too-heavy");
        assertProblem(() -> bidCheckoutService.checkout(s.uid(), card(trip.getId(), "31", 40, 40, 60),
                new MockHttpServletRequest()), HttpStatus.UNPROCESSABLE_ENTITY, "parcel-too-heavy");
    }

    @Test
    @DisplayName("aucun plafond de prix pour un bateau : 2 m³ à 20 000 € = 40 000 € net accepté au devis")
    void boat_noPriceCeiling() {
        AnnouncementEntity trip = boatPerM3("EUR", "20000", "2.500");
        Sender s = sender();

        BidQuoteResponse quote = bidService.quote(s.uid(),
                new BidQuoteRequest(trip.getId(), null, null, null, 100, 100, 200));

        assertThat(quote.netEur()).isEqualByComparingTo("40000.00");
    }

    @Test
    @DisplayName("prix au volume hors borne technique (NUMERIC(10,2)) → 422 volume-price-too-high, jamais 500 SQL")
    void boat_priceBeyondTechnicalBound_422() {
        AnnouncementEntity trip = boatPerM3("EUR", "99999999.99", "2.500");
        Sender s = sender();

        assertProblem(() -> bidCheckoutService.checkout(s.uid(), card(trip.getId(), null, 100, 100, 200),
                new MockHttpServletRequest()), HttpStatus.UNPROCESSABLE_ENTITY, "volume-price-too-high");
        assertThat(bids(trip.getId())).isZero();
    }

    // ── Réservation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("paiement carte : colis au volume serveur (0,096), escrow au net recalculé (57,60), prix payé = devis")
    void checkout_boatPerM3_escrowAtVolumePrice() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();
        BidQuoteResponse quote = bidService.quote(s.uid(),
                new BidQuoteRequest(trip.getId(), new BigDecimal("12"), null, null, 40, 40, 60));

        BidCheckoutResponse checkout = bidCheckoutService.checkout(s.uid(), card(trip.getId(), "12", 40, 40, 60),
                new MockHttpServletRequest());

        Map<String, Object> bid = jdbc.queryForMap(
                "SELECT volume_m3, weight_kg, pricing_mode, status FROM bids WHERE id = ?", checkout.bidId());
        assertThat((BigDecimal) bid.get("volume_m3")).isEqualByComparingTo("0.096");
        assertThat((BigDecimal) bid.get("weight_kg")).isEqualByComparingTo("12");
        assertThat(bid.get("pricing_mode")).isEqualTo("KG");
        assertThat(bid.get("status")).isEqualTo("AWAITING_PAYMENT");
        Map<String, Object> payment = jdbc.queryForMap(
                "SELECT amount, commission_amount FROM payments WHERE bid_id = ?", checkout.bidId());
        BigDecimal gross = (BigDecimal) payment.get("amount");
        BigDecimal commission = (BigDecimal) payment.get("commission_amount");
        assertThat(gross.subtract(commission)).isEqualByComparingTo("57.60");
        assertThat(gross).isEqualByComparingTo(quote.totalEur());
        // Rien n'est pris avant l'acceptation du voyageur (la réservation carte suit l'autorisation).
        assertThat(availableVolume(trip.getId())).isEqualByComparingTo("2.500");
    }

    @Test
    @DisplayName("offre ferme : volume serveur sur le colis, réponse volumeM3 et total frais inclus, aucun tarif au kilo")
    void createBid_boatPerM3_storesServerVolume() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();
        BidQuoteResponse quote = bidService.quote(s.uid(),
                new BidQuoteRequest(trip.getId(), new BigDecimal("12"), null, null, 40, 40, 60));

        BidResponse created = bidService.createBid(trip.getId(), s.uid(), offer("12", "CASH", 40, 40, 60),
                new MockHttpServletRequest());

        assertThat(created.volumeM3()).isEqualByComparingTo("0.096");
        assertThat(created.pricePerKgSenderEur()).isNull();
        assertThat(created.totalNetAmountEur()).as("jamais le net à l'expéditeur").isNull();
        assertThat(created.totalSenderAmountEur()).isEqualByComparingTo(quote.totalEur());
        assertThat(jdbc.queryForObject("SELECT volume_m3 FROM bids WHERE id = ?", BigDecimal.class, created.id()))
                .isEqualByComparingTo("0.096");
    }

    @Test
    @DisplayName("trajet au kilo : dimensions ignorées, aucun volume ; clés volumeM3 / volumeNetEur toujours sérialisées")
    void kgTrip_dimensionsIgnored_keysAlwaysSerialized() throws Exception {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        trip.setPricePerM3(null);
        trip.setVolumeCapacityM3(null);
        trip.setAvailableVolumeM3(null);
        trip.setArrivalDateMax(null);
        trip.setTransportMode(TransportMode.PLANE);
        trip.setMaxParcelWeightKg(null);
        trip.setAvailableKg(new BigDecimal("20.00"));
        trip.setTotalKg(new BigDecimal("20.00"));
        trip.setPricePerKg(new BigDecimal("8.00"));
        announcementRepository.saveAndFlush(trip);
        Sender s = sender();

        BidQuoteResponse quote = bidService.quote(s.uid(),
                new BidQuoteRequest(trip.getId(), new BigDecimal("5"), null, null, 40, 40, 60));
        BidResponse created = bidService.createBid(trip.getId(), s.uid(), offer("5", "CASH", 40, 40, 60),
                new MockHttpServletRequest());

        assertThat(quote.netEur()).isEqualByComparingTo("40.00");
        assertThat(quote.volumeNetEur()).isEqualByComparingTo("0");
        assertThat(created.volumeM3()).isNull();
        assertThat(availableVolume(trip.getId())).isNull();
        com.fasterxml.jackson.databind.JsonNode bidJson = objectMapper.valueToTree(created);
        assertThat(bidJson.has("volumeM3")).isTrue();
        assertThat(bidJson.get("volumeM3").isNull()).isTrue();
        assertThat(objectMapper.valueToTree(quote).has("volumeNetEur")).isTrue();
    }

    @Test
    @DisplayName("proposition de prix : volume serveur sur le colis du fil, le prix proposé fait foi")
    void propose_boatPerM3_storesVolume() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        trip.setNegotiable(true);
        announcementRepository.saveAndFlush(trip);
        Sender s = sender();

        var thread = bidNegotiationService.propose(trip.getId(), s.uid(),
                new BidNegotiationStartRequest(null, "Vêtements", "OTHER", "Awa", "+221771234567", true,
                        null, null, null, null, new BigDecimal("50"), null, null, null, 40, 40, 60),
                new MockHttpServletRequest());

        assertThat(jdbc.queryForObject("SELECT volume_m3 FROM bids WHERE id = ?", BigDecimal.class, thread.bidId()))
                .isEqualByComparingTo("0.096");
    }

    // ── Acceptation et rendu ─────────────────────────────────────────────────

    @Test
    @DisplayName("carte acceptée : volume pris (2,5 - 0,096), puis rendu à l'annulation")
    void cardAccepted_takesVolume_cancelRestores() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        UUID bidId = escrowedParcel(trip, "0.096");

        bidService.acceptBid(bidId, travelerUid);
        assertThat(availableVolume(trip.getId())).isEqualByComparingTo("2.404");

        bidService.cancelBid(bidId, travelerUid);
        assertThat(availableVolume(trip.getId())).isEqualByComparingTo("2.500");
        assertThat(tripRow(trip.getId()).get("available_kg")).isNull();
    }

    @Test
    @DisplayName("dernier m³ pris : trajet FULL ; acceptation suivante refusée en 409 insufficient-volume")
    void lastM3_full_thenNextRefused() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "0.100");
        UUID first = escrowedParcel(trip, "0.100");
        UUID second = escrowedParcel(trip, "0.050");

        bidService.acceptBid(first, travelerUid);

        assertThat(availableVolume(trip.getId())).isEqualByComparingTo("0");
        assertThat(tripRow(trip.getId()).get("status")).isEqualTo("FULL");
        assertProblem(() -> bidService.acceptBid(second, travelerUid), HttpStatus.CONFLICT, "insufficient-volume");
    }

    @Test
    @DisplayName("concurrentCheckouts_lastM3_onlyOneAccepted : deux acceptations parallèles sur 0,1 m³, une seule gagne")
    void concurrentCheckouts_lastM3_onlyOneAccepted() throws Exception {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "0.100");
        List<UUID> parcels = List.of(escrowedParcel(trip, "0.100"), escrowedParcel(trip, "0.100"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = new ArrayList<>();
            for (UUID bidId : parcels) {
                Callable<String> accept = () -> {
                    start.await();
                    try {
                        bidService.acceptBid(bidId, travelerUid);
                        return "ACCEPTED";
                    } catch (YadonyBusinessException e) {
                        return e.getStatus().value() + " " + e.getErrorCode();
                    }
                };
                outcomes.add(pool.submit(accept));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : outcomes) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }

            assertThat(results).containsExactlyInAnyOrder("ACCEPTED", "409 insufficient-volume");
        } finally {
            pool.shutdownNow();
        }
        assertThat(availableVolume(trip.getId())).isEqualByComparingTo("0");
        assertThat(tripRow(trip.getId()).get("status")).isEqualTo("FULL");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bids WHERE announcement_id = ? AND status = 'ACCEPTED'", Long.class, trip.getId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("espèces acceptées : commission sur le net au volume, volume pris")
    void cashAccepted_takesVolume() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();
        BidResponse created = bidService.createBid(trip.getId(), s.uid(), offer(null, "CASH", 40, 40, 60),
                new MockHttpServletRequest());
        WalletAccountEntity wallet = new WalletAccountEntity();
        wallet.setUserId(travelerId);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("500"));
        walletAccountRepository.saveAndFlush(wallet);

        assertThat(cashCommissionService.acceptCashBid(created.id(), travelerId, CommissionSource.WALLET_FIRST)
                .status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);

        assertThat(availableVolume(trip.getId())).isEqualByComparingTo("2.404");
        BigDecimal balance = jdbc.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE user_id = ? AND currency = 'EUR'", BigDecimal.class, travelerId);
        assertThat(balance).as("commission prélevée sur le net au volume").isLessThan(new BigDecimal("500"));
    }

    @Test
    @DisplayName("fil payé sur un trajet au m³ existant : colis au volume de la demande, volume pris, prix = accord")
    void threadMaterialised_onLinkedPerM3Trip_takesRequestVolume() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        UUID senderId = sender().id();
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,
                    weight_kg, parcel_size, content_category, transport_mode, status, length_cm, width_cm,
                    height_cm, volume_m3)
                VALUES (?, ?, 'Marseille', 'Dakar', CURRENT_DATE + 10, 5, 'LARGE', 'CLOTHES', 'BOAT', 'OPEN',
                    100, 50, 10, 0.050)
                """, requestId, senderId);
        UUID threadId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO negotiation_threads (id, package_request_id, traveler_id, traveler_travel_date,
                    traveler_available_kg, status, current_price_eur, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_DATE + 10, 40, 'OPEN', 45.0, now(), now())
                """, threadId, requestId, travelerId);

        threadAcceptedBidListener.onPackageRequestAccepted(new PackageRequestAcceptedEvent(
                threadId, requestId, senderId, travelerId, new BigDecimal("45.00"), trip.getId(),
                new BigDecimal("5"), "Vêtements", "CLOTHING", "pi_thread_" + threadId, "Awa", "+221771234567",
                LocalDateTime.now(ZoneOffset.UTC), "203.0.113.7", PaymentMethod.STRIPE, List.of(), null, null,
                null, "EUR", null, null, null, new BigDecimal("0.050")));

        BidEntity bid = bidRepository.findByLinkedNegotiationThreadId(threadId).orElseThrow();
        assertThat(bid.getVolumeM3()).isEqualByComparingTo("0.050");
        assertThat(bid.getNegotiatedNetEur()).isEqualByComparingTo("45.00");
        assertThat(availableVolume(trip.getId())).isEqualByComparingTo("2.450");
    }
}
