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
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.tracking.dto.QrScanRequest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * FLUTTER-JV / YADONY-BACK-STAGING-8 : deux POST /tracking/events DEPART identiques à 0,5 s
 * d'écart (envoi direct + file hors ligne de l'app), sur un vrai Postgres (index unique
 * partiel {@code uq_tracking_one_depart_per_bid} de V48, absent de H2).
 *
 * <p>Pas de {@code @Transactional} sur la classe : chaque scan commite pour de vrai, c'est
 * à l'insert concurrent que la violation d'unicité ressortait en 500.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class TrackingScanConcurrencyIT {

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

    @Autowired private TrackingController trackingController;
    @Autowired private UserRepository userRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.yadony.api.common.i18n.MessagesResolver messagesResolver;
    @MockitoBean private NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean private GeoNamesDataLoader geoNamesDataLoader;

    @Test
    void deuxScansDepartSimultanes_unSeulEnregistre_lesDeuxReussissent_effetsUneFois() throws Exception {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = persistAcceptedBid(traveler);
        QrScanRequest depart = new QrScanRequest(bid.getId(), TrackingEventType.DEPART,
                null, null, null, null, null, ScanMethod.QR, null);

        int threads = 2;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        List<UUID> eventIds = new ArrayList<>();
        try {
            List<Future<org.springframework.http.ResponseEntity<com.yadony.api.tracking.dto.TrackingEventResponse>>>
                    calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return scanAs(traveler, depart);
                }));
            }
            for (var call : calls) {
                var response = call.get(30, TimeUnit.SECONDS);
                statuses.add(response.getStatusCode().value());
                eventIds.add(response.getBody().id());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 200);
        assertThat(eventIds).hasSize(2).allMatch(id -> id.equals(eventIds.get(0)));
        assertThat(count("SELECT COUNT(*) FROM tracking_events WHERE bid_id = ? AND event_type = 'DEPART'",
                bid.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'CODE_GENERATED'",
                bid.getId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'SCAN_DEPART' AND entity_id = ?",
                eventIds.get(0))).isEqualTo(1);
        BidEntity after = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(after.getConfirmationCode()).isNotNull();
        verify(notificationDispatcher, org.mockito.Mockito.times(1)).notifyUser(any(), any(), any(), any());
    }

    @Test
    void scanDepartRejoue_renvoie200_etGardeLeCode() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = persistAcceptedBid(traveler);
        QrScanRequest depart = new QrScanRequest(bid.getId(), TrackingEventType.DEPART,
                null, null, null, null, null, ScanMethod.QR, null);

        var first = scanAs(traveler, depart);
        String code = bidRepository.findById(bid.getId()).orElseThrow().getConfirmationCode();
        var replay = scanAs(traveler, depart);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getConfirmationCode()).isEqualTo(code);
    }

    @org.junit.jupiter.api.BeforeEach
    void stubMessages() {
        org.mockito.Mockito.when(notificationDispatcher.messagesFor(any()))
                .thenAnswer(inv -> messagesResolver.forUser(inv.getArgument(0)));
    }

    /** Le contrôleur est sous {@code @PreAuthorize("hasRole('TRAVELER')")} : contexte par thread. */
    private org.springframework.http.ResponseEntity<com.yadony.api.tracking.dto.TrackingEventResponse> scanAs(
            UserEntity traveler, QrScanRequest request) {
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                traveler.getFirebaseUid(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TRAVELER"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            return trackingController.scan(request, traveler.getFirebaseUid());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private int count(String sql, UUID id) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("tracking-scan-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private BidEntity persistAcceptedBid(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
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
        a.setStatus(AnnouncementStatus.ACTIVE);
        a = announcementRepository.saveAndFlush(a);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(a.getId());
        bid.setSenderId(persistUser(Role.SENDER).getId());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        return bidRepository.saveAndFlush(bid);
    }
}
