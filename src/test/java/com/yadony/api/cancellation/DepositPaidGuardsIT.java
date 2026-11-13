package com.yadony.api.cancellation;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.dto.CancellationRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.city.GeoNamesDataLoader;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.DepositPaidTripCancelAlertListener;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gardes « aucun remboursement après versement au dépôt » sur la vraie pile (port branché par Spring, Postgres, verrous
 * réels) : annulation après remise, annulation d'un colis remis, refus du colis et annulation du trajet refusés en 409
 * {@code deposit-paid-no-refund} quand le paiement plateforme est libéré ; alerte admin unique pour le trajet ; un colis
 * espèces payé au dépôt reste annulable (aucun argent plateforme).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Aucun remboursement automatique après le versement au dépôt (PostgreSQL)")
class DepositPaidGuardsIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
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

    @Autowired CancellationService cancellationService;
    @Autowired BidService bidService;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired RescheduleDecisionService rescheduleDecisionService;
    @Autowired com.yadony.api.tracking.TrackingEventRepository trackingEventRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

    @Test
    @DisplayName("carte libérée au dépôt : annulations (expéditeur, voyageur) et refus du colis → 409, rien ne change")
    void depositPaid_cancelAndRefuse_409() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistBoatTrip(traveler);
        BidEntity bid = persistDepositedBid(trip, sender, PaymentMethod.STRIPE);
        persistPayment(bid, PaymentStatus.RELEASED);

        assertDepositPaid(() -> cancellationService.cancelAfterHandover(sender.getFirebaseUid(), bid.getId()));
        assertDepositPaid(() -> cancellationService.cancelAfterHandover(traveler.getFirebaseUid(), bid.getId()));
        assertDepositPaid(() -> bidService.cancelBid(bid.getId(), sender.getFirebaseUid()));
        assertDepositPaid(() -> bidService.refuseParcel(bid.getId(), traveler.getFirebaseUid(), "abîmé", null));

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(count("SELECT COUNT(*) FROM cancellations WHERE bid_id = ?", bid.getId())).isZero();
    }

    @Test
    @DisplayName("annulation du trajet avec un colis payé au dépôt → 409, trajet intact, une seule alerte admin")
    void cancelTrip_withDepositedParcel_409AndAlert() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        AnnouncementEntity trip = persistBoatTrip(traveler);
        BidEntity bid = persistDepositedBid(trip, persistUser(Role.SENDER), PaymentMethod.MOBILE_MONEY);
        persistPayment(bid, PaymentStatus.RELEASED);
        CancellationRequest request = new CancellationRequest(trip.getId(), "Bateau annulé");

        assertDepositPaid(() -> cancellationService.cancelTrip(traveler.getFirebaseUid(), request));
        assertDepositPaid(() -> cancellationService.cancelTrip(traveler.getFirebaseUid(), request));

        assertThat(announcementRepository.findById(trip.getId()).orElseThrow().getStatus())
                .isEqualTo(AnnouncementStatus.ACTIVE);
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(count("SELECT COUNT(*) FROM admin_alerts WHERE type = ? AND resolved = false",
                DepositPaidTripCancelAlertListener.ALERT_PREFIX + trip.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("report du trajet puis retrait de l'expéditeur d'un colis payé au dépôt → 409, colis intact")
    void withdrawAfterReschedule_depositPaid_409() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistBoatTrip(traveler);
        BidEntity bid = persistDepositedBid(trip, sender, PaymentMethod.STRIPE);
        persistPayment(bid, PaymentStatus.RELEASED);
        UUID rescheduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trip_reschedules (id, announcement_id, traveler_id, reason, previous_departure_date,
                                              new_departure_date, created_at)
                VALUES (?, ?, ?, 'POSTPONED', ?, ?, now())
                """, rescheduleId, trip.getId(), traveler.getId(), LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(3));
        jdbc.update("UPDATE bids SET pending_reschedule_id = ? WHERE id = ?", rescheduleId, bid.getId());

        assertDepositPaid(() -> rescheduleDecisionService.decide(sender.getFirebaseUid(), bid.getId(),
                RescheduleDecision.WITHDRAW));

        BidEntity after = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(after.getPendingRescheduleId()).isEqualTo(rescheduleId);
        assertThat(after.getReturnCode()).isNull();
        assertThat(count("SELECT COUNT(*) FROM cancellations WHERE bid_id = ?", bid.getId())).isZero();
    }

    @Test
    @DisplayName("colis payé au dépôt encore en séquestre (versement pas parti) : annulation après remise permise")
    void depositStillEscrow_cancelAllowed() {
        UserEntity sender = persistUser(Role.SENDER);
        BidEntity bid = persistDepositedBid(persistBoatTrip(persistUser(Role.TRAVELER)), sender, PaymentMethod.STRIPE);
        persistPayment(bid, PaymentStatus.ESCROW);

        cancellationService.cancelAfterHandover(sender.getFirebaseUid(), bid.getId());

        assertThat(count("SELECT COUNT(*) FROM cancellations WHERE bid_id = ?", bid.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("espèces payées au dépôt : aucun argent plateforme, annulation après remise permise")
    void cashDeposit_cancelAllowed() {
        UserEntity sender = persistUser(Role.SENDER);
        BidEntity bid = persistDepositedBid(persistBoatTrip(persistUser(Role.TRAVELER)), sender, PaymentMethod.CASH);

        cancellationService.cancelAfterHandover(sender.getFirebaseUid(), bid.getId());

        assertThat(count("SELECT COUNT(*) FROM cancellations WHERE bid_id = ?", bid.getId())).isEqualTo(1);
    }

    private static void assertDepositPaid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getErrorCode()).isEqualTo("deposit-paid-no-refund");
        });
    }

    private long count(String sql, Object arg) {
        Long n = jdbc.queryForObject(sql, Long.class, arg);
        return n == null ? 0 : n;
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("deposit-guard-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    /** Trajet bateau au kilo, départ dans 3 jours (colis remis, trajet pas encore parti). */
    private AnnouncementEntity persistBoatTrip(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Marseille");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(3));
        a.setArrivalDate(LocalDate.now().plusDays(20));
        a.setArrivalDateMax(LocalDate.now().plusDays(30));
        a.setTransportMode(TransportMode.BOAT);
        a.setPickupAddressLabel("Port de Marseille");
        a.setPickupLat(new BigDecimal("43.300000"));
        a.setPickupLng(new BigDecimal("5.360000"));
        a.setDeliveryAddressLabel("Port de Dakar");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.440000"));
        a.setAvailableKg(new BigDecimal("97.00"));
        a.setTotalKg(new BigDecimal("100.00"));
        a.setPricePerKg(new BigDecimal("3.00"));
        a.setCurrency("EUR");
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.saveAndFlush(a);
    }

    /** Colis payé au dépôt (règle figée comme à l'acceptation) et déposé : HANDED_OVER. */
    private BidEntity persistDepositedBid(AnnouncementEntity trip, UserEntity sender, PaymentMethod method) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setCurrency("EUR");
        bid.setPaymentMethod(method);
        bid.setStatus(BidStatus.HANDED_OVER);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        ReflectionTestUtils.setField(bid, "payoutTrigger", PayoutTrigger.DEPOSIT);
        ReflectionTestUtils.setField(bid, "payoutTriggerFrozenAt", LocalDateTime.now());
        BidEntity saved = bidRepository.saveAndFlush(bid);
        // Vrai dépôt : étape DEPART enregistrée (photo du dépôt).
        com.yadony.api.tracking.TrackingEventEntity depart = new com.yadony.api.tracking.TrackingEventEntity();
        depart.setBidId(saved.getId());
        depart.setEventType(com.yadony.api.tracking.TrackingEventType.DEPART);
        depart.setScannedAt(LocalDateTime.now().minusHours(1));
        depart.setPhotoUrl("tracking/" + saved.getId() + "/deposit.jpg");
        trackingEventRepository.saveAndFlush(depart);
        return saved;
    }

    private void persistPayment(BidEntity bid, PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setRail(bid.getPaymentMethod() == PaymentMethod.MOBILE_MONEY ? PaymentRail.PAWAPAY : PaymentRail.STRIPE);
        p.setAmount(new BigDecimal("10.08"));
        p.setCommissionAmount(new BigDecimal("1.08"));
        p.setCurrency("EUR");
        p.setStatus(status);
        p.setLegacyDestinationCharge(false);
        paymentRepository.saveAndFlush(p);
    }
}
