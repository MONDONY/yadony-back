package com.yadony.api.tracking;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.city.GeoNamesDataLoader;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.events.AnnouncementCompletedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Effacement des positions sur un vrai Postgres, commits réels (pas de {@code @Transactional} sur
 * la classe) : prouve le déclenchement AFTER_COMMIT, le REQUIRES_NEW du service, le rollback, et la
 * validité PostgreSQL du DELETE par lots (LIMIT dans IN (sous-requête)).
 */
@SpringBootTest
@ActiveProfiles("e2e")
class TripPositionPurgeIT {

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

    @Autowired private UserRepository userRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate tx;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TripPositionPurgeService purgeService;
    @Autowired private Clock clock;
    @MockitoSpyBean private TripPositionSampleRepository sampleRepository;
    @MockitoBean private NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean private GeoNamesDataLoader geoNamesDataLoader;

    @Test
    void completedEventCommitsTheErasure() {
        UUID trip = persistTrip(AnnouncementStatus.COMPLETED).getId();
        persistSamples(trip, 3);

        tx.executeWithoutResult(s -> publisher.publishEvent(new AnnouncementCompletedEvent(trip)));

        assertThat(samplesOf(trip)).isZero();
    }

    @Test
    void rolledBackCompletionKeepsSamples() {
        UUID trip = persistTrip(AnnouncementStatus.IN_PROGRESS).getId();
        persistSamples(trip, 3);

        tx.executeWithoutResult(s -> {
            publisher.publishEvent(new AnnouncementCompletedEvent(trip));
            s.setRollbackOnly();
        });

        assertThat(samplesOf(trip)).isEqualTo(3);
    }

    @Test
    void deliveryOfLastParcelErasesSamplesEndToEnd() {
        AnnouncementEntity trip = persistTrip(AnnouncementStatus.IN_PROGRESS);
        persistSamples(trip.getId(), 3);
        BidEntity bid = persistBid(trip, BidStatus.COMPLETED);

        tx.executeWithoutResult(s -> publisher.publishEvent(
                new DeliveryConfirmedEvent(bid.getId(), bid.getSenderId(), trip.getTravelerId())));

        assertThat(announcementRepository.findById(trip.getId()).orElseThrow().getStatus())
                .isEqualTo(AnnouncementStatus.COMPLETED);
        assertThat(samplesOf(trip.getId())).isZero();
    }

    @Test
    void purgeFailureDoesNotUndoCompletion() {
        AnnouncementEntity trip = persistTrip(AnnouncementStatus.IN_PROGRESS);
        persistSamples(trip.getId(), 2);
        BidEntity bid = persistBid(trip, BidStatus.COMPLETED);
        doThrow(new IllegalStateException("db down")).when(sampleRepository).eraseByAnnouncementId(any());

        tx.executeWithoutResult(s -> publisher.publishEvent(
                new DeliveryConfirmedEvent(bid.getId(), bid.getSenderId(), trip.getTravelerId())));

        assertThat(announcementRepository.findById(trip.getId()).orElseThrow().getStatus())
                .isEqualTo(AnnouncementStatus.COMPLETED);
        assertThat(samplesOf(trip.getId())).isEqualTo(2);
    }

    @Test
    void safetyNetBatchQueryIsValidOnPostgres() {
        UUID closed = persistTrip(AnnouncementStatus.CANCELLED).getId();
        UUID active = persistTrip(AnnouncementStatus.IN_PROGRESS).getId();
        persistSamples(closed, 3);
        persistSamples(active, 2);

        int deleted = purgeService.purgeClosedAndExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(3);
        assertThat(samplesOf(closed)).isZero();
        assertThat(samplesOf(active)).isEqualTo(2);
        assertThat(OffsetDateTime.now(clock)).isNotNull();
    }

    private int samplesOf(UUID trip) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trip_position_samples WHERE announcement_id = ?", Integer.class, trip);
        return n == null ? 0 : n;
    }

    private void persistSamples(UUID trip, int count) {
        OffsetDateTime t0 = OffsetDateTime.now(clock).minusHours(5);
        for (int i = 0; i < count; i++) {
            sampleRepository.saveAndFlush(new TripPositionSampleEntity(
                    trip, t0.plusMinutes(i), "Lyon", "FR", 10, SampleSource.OUVERTURE));
        }
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("position-purge-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private AnnouncementEntity persistTrip(AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(persistUser(Role.TRAVELER).getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(1));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Plateau");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.440000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(status);
        return announcementRepository.saveAndFlush(a);
    }

    private BidEntity persistBid(AnnouncementEntity trip, BidStatus status) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(persistUser(Role.SENDER).getId());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(status);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        return bidRepository.saveAndFlush(bid);
    }
}
