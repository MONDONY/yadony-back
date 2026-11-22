package com.yadony.api.matching;

import com.stripe.model.PaymentIntent;
import com.yadony.api.common.RecipientAccountResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.matching.dto.BidCheckoutRequest;
import com.yadony.api.payments.StripeGateway;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Checkout carte avec {@code recipientUsername} sur PostgreSQL embarqué : le colis naît {@code AWAITING_PAYMENT}
 * avec le compte destinataire, sans numéro, et une ligne d'audit sans numéro ; compte inconnu : 404 sans colis ;
 * quota de recherche épuisé : 429 sans colis ; mélange des modes et voyageur désigné : 422.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Checkout carte : destinataire désigné par identifiant (Postgres)")
class BidCheckoutRecipientUsernameIT {

    private static EmbeddedPostgres postgres;

    @Autowired BidCheckoutService bidCheckoutService;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PlatformSettingsService platformSettings;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean StripeGateway stripeGateway;
    @MockitoSpyBean RecipientAccountResolver recipientResolver;

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

    private final Map<UUID, String> usernames = new java.util.HashMap<>();
    private int accountSeq;

    /** Compte destinataire d'identifiant unique (les comptes persistent d'un test à l'autre). */
    private Sender recipientAccount(String prefix) {
        UUID id = UUID.randomUUID();
        String username = prefix + "." + (++accountSeq) + id.toString().substring(0, 5);
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED')
                """, id, "uid-recipient-" + id, username);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'SENDER')", id);
        usernames.put(id, username);
        return new Sender(id, "uid-recipient-" + id);
    }

    private static BidCheckoutRequest byUsername(UUID tripId, String name, String phone, String username) {
        return new BidCheckoutRequest(tripId, null, "Vêtements", "OTHER", name, phone, true, null, null, null, null,
                40, 40, 60, username);
    }

    private long bids(UUID tripId) {
        return jdbc.queryForObject("SELECT count(*) FROM bids WHERE announcement_id = ?", Long.class, tripId);
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
                                      String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getErrorCode()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("identifiant seul : colis AWAITING_PAYMENT, compte destinataire, @identifiant, numéro nul, audit")
    void usernameOnly_createsParcelForTheAccount() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();
        Sender recipient = recipientAccount("awa");

        var response = bidCheckoutService.checkout(s.uid(), byUsername(trip.getId(), null, null, usernames.get(recipient.id())),
                new MockHttpServletRequest());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, recipient_user_id, recipient_name, recipient_phone FROM bids WHERE id = ?",
                response.bidId());
        assertThat(row.get("status")).isEqualTo("AWAITING_PAYMENT");
        assertThat(row.get("recipient_user_id")).isEqualTo(recipient.id());
        assertThat(row.get("recipient_name")).isEqualTo("@" + usernames.get(recipient.id()));
        assertThat(row.get("recipient_phone")).isNull();
        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT actor_id, payload::text AS payload FROM audit_log WHERE action = "
                        + "'RECIPIENT_DESIGNATED_BY_USERNAME' AND entity_id = ?", response.bidId());
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("actor_id")).isEqualTo(s.id());
        assertThat((String) audit.get(0).get("payload")).contains(recipient.id().toString());
    }

    @Test
    @DisplayName("compte inconnu : 404 user-not-found, aucun colis")
    void unknownAccount_404_noParcel() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();

        assertProblem(() -> bidCheckoutService.checkout(s.uid(), byUsername(trip.getId(), null, null, "personne.ici"),
                new MockHttpServletRequest()), HttpStatus.NOT_FOUND, "user-not-found");

        assertThat(bids(trip.getId())).isZero();
    }

    @Test
    @DisplayName("quota de recherche épuisé : 429, aucun colis")
    void lookupQuotaExhausted_429_noParcel() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();
        Sender recipient = recipientAccount("awa");
        Mockito.doThrow(YadonyBusinessException.localized(HttpStatus.TOO_MANY_REQUESTS, "recipient-lookup-quota",
                        "problem.recipient-lookup-quota"))
                .when(recipientResolver).resolve(any(), any());

        assertProblem(() -> bidCheckoutService.checkout(s.uid(), byUsername(trip.getId(), null, null, usernames.get(recipient.id())),
                new MockHttpServletRequest()), HttpStatus.TOO_MANY_REQUESTS, "recipient-lookup-quota");

        assertThat(bids(trip.getId())).isZero();
    }

    @Test
    @DisplayName("identifiant + nom/numéro : 422 recipient-mode-conflict ; voyageur désigné : 422 recipient-is-traveler")
    void conflictAndTraveler_422_noParcel() {
        AnnouncementEntity trip = boatPerM3("EUR", "600", "2.500");
        Sender s = sender();
        Sender recipient = recipientAccount("awa");
        String travelerUsername = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class,
                travelerId);

        assertProblem(() -> bidCheckoutService.checkout(s.uid(),
                        byUsername(trip.getId(), "Awa", "+221771234567", usernames.get(recipient.id())), new MockHttpServletRequest()),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-mode-conflict");
        assertProblem(() -> bidCheckoutService.checkout(s.uid(),
                        byUsername(trip.getId(), null, null, travelerUsername), new MockHttpServletRequest()),
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-is-traveler");

        assertThat(bids(trip.getId())).isZero();
    }
}
