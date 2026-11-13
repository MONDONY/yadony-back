package com.yadony.api.tracking;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.city.GeoNamesDataLoader;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.tracking.dto.QrScanRequest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Dépôt d'un colis payé au dépôt, sur la vraie pile (service transactionnel, base, audit) : un seul DEPART, un
 * seul dépôt publié même quand l'app envoie le scan deux fois en même temps (envoi direct + file hors ligne),
 * aucun dépôt sans photo ni pour un colis payé à la livraison.
 *
 * <p>L'événement {@code ParcelDepositedEvent} est publié dans le même bloc que l'audit {@code PARCEL_DEPOSITED},
 * dans la même transaction (vérifié en unitaire par {@code TrackingServiceDepositTest}) : une ligne d'audit
 * commitée = un dépôt publié. Vrai Postgres (verrous {@code FOR NO KEY UPDATE} des gardes de remise et index unique
 * {@code uq_tracking_one_depart_per_bid}, absents de H2), même montage que {@code TrackingScanConcurrencyIT}.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Dépôt d'un colis payé au dépôt : un seul dépôt publié, photo obligatoire")
class TrackingDepositIT {

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

    @Autowired TrackingService trackingService;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.yadony.api.common.media.MediaUploadRepository mediaUploads;
    @Autowired com.yadony.api.common.i18n.MessagesResolver messagesResolver;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

    @BeforeEach
    void stubMessages() {
        org.mockito.Mockito.when(notificationDispatcher.messagesFor(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> messagesResolver.forUser(inv.getArgument(0)));
    }

    @Test
    @DisplayName("deux DEPART simultanés d'un colis DEPOSIT : un DEPART, un dépôt, colis HANDED_OVER")
    void twoDepartScansInParallel_oneDeposit() throws Exception {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = persistAcceptedBid(traveler, PayoutTrigger.DEPOSIT);
        QrScanRequest depart = depart(bid, uploadedPhoto(bid, traveler), bid.getQrToken());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> created = new ArrayList<>();
        try {
            List<Future<Boolean>> calls = List.of(
                    pool.submit(() -> { start.await(); return trackingService.recordScan(depart, traveler.getFirebaseUid()).created(); }),
                    pool.submit(() -> { start.await(); return trackingService.recordScan(depart, traveler.getFirebaseUid()).created(); }));
            start.countDown();
            for (Future<Boolean> call : calls) {
                created.add(call.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        // Le second attend le verrou du colis, voit le DEPART commité et le rejoue sans effet.
        assertThat(created).containsExactlyInAnyOrder(true, false);
        assertThat(count("SELECT COUNT(*) FROM tracking_events WHERE bid_id = ? AND event_type = 'DEPART'", bid.getId()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'PARCEL_DEPOSITED'", bid.getId()))
                .isEqualTo(1);
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);

        // Rejeu tardif (file hors ligne) : l'étape existante, aucun nouveau dépôt.
        assertThat(trackingService.recordScan(depart(bid, null, null), traveler.getFirebaseUid()).created()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'PARCEL_DEPOSITED'", bid.getId()))
                .isEqualTo(1);
        assertThat(bidRepository.findDepartScannedAt(bid.getId())).isNotNull();
    }

    @Test
    @DisplayName("dépôt sans photo : 422 boat-deposit-photo-required, ni DEPART ni dépôt, colis toujours ACCEPTED")
    void depositWithoutPhoto_refused_nothingWritten() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = persistAcceptedBid(traveler, PayoutTrigger.DEPOSIT);

        Throwable thrown = catchThrowable(() -> trackingService.recordScan(depart(bid, null, bid.getQrToken()),
                traveler.getFirebaseUid()));

        assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
        assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("boat-deposit-photo-required");
        assertThat(count("SELECT COUNT(*) FROM tracking_events WHERE bid_id = ?", bid.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'PARCEL_DEPOSITED'", bid.getId()))
                .isZero();
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.ACCEPTED);
        assertThat(bidRepository.findDepartScannedAt(bid.getId())).isNull();
    }

    @Test
    @DisplayName("transporteur seul (identifiant et numéro de suivi, sans le jeton du QR) : 422, ni DEPART ni dépôt")
    void depositWithoutQrToken_refused_nothingWritten() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = persistAcceptedBid(traveler, PayoutTrigger.DEPOSIT);
        String photo = uploadedPhoto(bid, traveler);
        // Ce que le transporteur peut obtenir seul : l'identifiant (sa fiche colis) et le numéro (page publique).
        QrScanRequest manual = new QrScanRequest(bid.getId(), TrackingEventType.DEPART, null, null, null, photo, null,
                ScanMethod.MANUAL, bid.getTrackingNumber(), null);
        QrScanRequest guessed = depart(bid, photo, UUID.randomUUID().toString());

        for (QrScanRequest attempt : List.of(manual, guessed)) {
            Throwable thrown = catchThrowable(() -> trackingService.recordScan(attempt, traveler.getFirebaseUid()));
            assertThat(thrown).isInstanceOf(YadonyBusinessException.class);
            assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("boat-deposit-qr-required");
        }
        assertThat(count("SELECT COUNT(*) FROM tracking_events WHERE bid_id = ?", bid.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'PARCEL_DEPOSITED'", bid.getId()))
                .isZero();
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.ACCEPTED);
    }

    @Test
    @DisplayName("photo non téléversée par le transporteur (clé fabriquée, ou envoyée par un autre compte) : 422")
    void depositWithPhotoNotUploadedByCarrier_refused() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = persistAcceptedBid(traveler, PayoutTrigger.DEPOSIT);
        String byAnother = uploadedPhoto(bid, persistUser(Role.SENDER));
        String forged = "tracking/" + bid.getId() + "/1731400001_forged.jpg";

        for (String photo : List.of(byAnother, forged)) {
            Throwable thrown = catchThrowable(() -> trackingService.recordScan(depart(bid, photo, bid.getQrToken()),
                    traveler.getFirebaseUid()));
            assertThat(((YadonyBusinessException) thrown).getErrorCode()).isEqualTo("boat-deposit-photo-required");
        }
        assertThat(count("SELECT COUNT(*) FROM tracking_events WHERE bid_id = ?", bid.getId())).isZero();
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.ACCEPTED);
    }

    @Test
    @DisplayName("colis payé à la livraison : DEPART sans photo accepté, aucun dépôt publié")
    void deliveryBid_departWithoutPhoto_noDeposit() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = persistAcceptedBid(traveler, PayoutTrigger.DELIVERY);

        assertThat(trackingService.recordScan(depart(bid, null, null), traveler.getFirebaseUid()).created()).isTrue();

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'PARCEL_DEPOSITED'", bid.getId()))
                .isZero();
    }

    // ── Gabarits ─────────────────────────────────────────────────────────────

    private static QrScanRequest depart(BidEntity bid, String photo, String qrToken) {
        return new QrScanRequest(bid.getId(), TrackingEventType.DEPART, null, null, null, photo, null,
                ScanMethod.QR, null, qrToken);
    }

    private static String photoOf(BidEntity bid) {
        return "tracking/" + bid.getId() + "/1731400000_DEPART.jpg";
    }

    /** Photo inscrite au registre comme par {@code POST /storage/upload/tracking} du transporteur. */
    private String uploadedPhoto(BidEntity bid, UserEntity uploader) {
        String key = photoOf(bid);
        mediaUploads.saveAndFlush(new com.yadony.api.common.media.MediaUploadEntity(key, uploader.getId(),
                com.yadony.api.common.media.MediaPurpose.TRACKING, bid.getId(), 48_213L));
        return key;
    }

    private int count(String sql, UUID id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("tracking-deposit-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private BidEntity persistAcceptedBid(UserEntity traveler, PayoutTrigger trigger) {
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
        a.setAvailableKg(new BigDecimal("100.00"));
        a.setTotalKg(new BigDecimal("100.00"));
        a.setPricePerKg(new BigDecimal("3.00"));
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
        // Règle figée comme à l'acceptation (PayoutTriggerPolicy#freeze, réservé au paquet matching).
        ReflectionTestUtils.setField(bid, "payoutTrigger", trigger);
        ReflectionTestUtils.setField(bid, "payoutTriggerFrozenAt", LocalDateTime.now());
        return bidRepository.saveAndFlush(bid);
    }
}
