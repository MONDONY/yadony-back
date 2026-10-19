package com.yadony.api.payments.supplement;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.tracking.TrackingEventType;
import com.yadony.api.tracking.TrackingService;
import com.yadony.api.tracking.dto.QrScanRequest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Course entre une demande de supplément et le scan de remise, sur un vrai PostgreSQL (V338 et
 * mapping de l'entité validés au passage) : les deux prennent le verrou du colis. Soit la demande
 * passe d'abord et le scan est refusé en 409 {@code handover-supplement-pending} ; soit le scan
 * passe d'abord et la demande est créée APRÈS lui (colis remis avant le départ). Jamais un scan
 * enregistré alors qu'une demande ouverte le précédait.
 */
@SpringBootTest(properties = "yadony.bid-supplement.enabled=true")
@ActiveProfiles("e2e")
class BidSupplementConcurrencyIT {

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

    @Autowired BidSupplementService supplementService;
    @Autowired TrackingService trackingService;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired BidSupplementReconciliationJob reconciliationJob;

    @RepeatedTest(3)
    void demandeEtScanSimultanes_jamaisDeRemiseAvecUneDemandeOuverteAnterieure() throws Exception {
        UserEntity traveler = persistUser("acct_" + UUID.randomUUID());
        UserEntity sender = persistUser(null);
        UUID bidId = persistAcceptedBid(persistAnnouncement(traveler.getId()), sender.getId());
        CreateSupplementRequest request = new CreateSupplementRequest(new BigDecimal("15"),
                BidSupplementReason.GRID_ITEM, null, "Téléphone", "tracking/" + bidId + "/1_supplement.jpg");

        CyclicBarrier start = new CyclicBarrier(2);
        AtomicReference<Throwable> createError = new AtomicReference<>();
        AtomicReference<Throwable> scanError = new AtomicReference<>();
        Thread create = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                supplementService.create(bidId, traveler.getFirebaseUid(), request);
            } catch (Throwable t) {
                createError.set(t);
            }
        });
        Thread scan = new Thread(() -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                trackingService.recordScan(new QrScanRequest(bidId, TrackingEventType.DEPART,
                        null, null, null, null, null), traveler.getFirebaseUid());
            } catch (Throwable t) {
                scanError.set(t);
            }
        });
        create.start();
        scan.start();
        create.join(30_000);
        scan.join(30_000);

        assertThat(createError.get()).as("la demande ne doit jamais échouer ici").isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bid_supplements WHERE bid_id = ?", Integer.class, bidId))
                .isEqualTo(1);

        if (scanError.get() != null) {
            assertThat(scanError.get()).isInstanceOfSatisfying(YadonyBusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo("handover-supplement-pending"));
            assertThat(jdbc.queryForObject("SELECT status FROM bids WHERE id = ?", String.class, bidId))
                    .isEqualTo("ACCEPTED");
        } else {
            // scanned_at est un TIMESTAMPTZ, created_at un TIMESTAMP écrit en UTC (hibernate.jdbc.time_zone) :
            // on compare les deux en heure UTC.
            Boolean afterHandover = jdbc.queryForObject("SELECT s.created_at >= (e.scanned_at AT TIME ZONE 'UTC') "
                    + "FROM bid_supplements s JOIN tracking_events e ON e.bid_id = s.bid_id "
                    + "AND e.event_type = 'DEPART' WHERE s.bid_id = ?", Boolean.class, bidId);
            assertThat(afterHandover).as("la demande vient après la remise").isTrue();
        }
    }

    @org.junit.jupiter.api.Test
    void demandeOuverte_puisScan_remiseRefuseeEn409_puisAutoriseeApresRetrait() {
        UserEntity traveler = persistUser("acct_" + UUID.randomUUID());
        UserEntity sender = persistUser(null);
        UUID bidId = persistAcceptedBid(persistAnnouncement(traveler.getId()), sender.getId());
        var supplement = supplementService.create(bidId, traveler.getFirebaseUid(), new CreateSupplementRequest(
                new BigDecimal("15"), BidSupplementReason.OTHER, null, null, "tracking/" + bidId + "/1.jpg"));
        QrScanRequest depart = new QrScanRequest(bidId, TrackingEventType.DEPART, null, null, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> trackingService.recordScan(depart, traveler.getFirebaseUid()))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("handover-supplement-pending"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking_events WHERE bid_id = ?", Integer.class, bidId))
                .isZero();

        supplementService.withdraw(bidId, supplement.id(), traveler.getFirebaseUid());
        assertThat(trackingService.recordScan(depart, traveler.getFirebaseUid()).created()).isTrue();
    }

    @org.junit.jupiter.api.Test
    void demandeEchueNonFermee_laRemisePasse_etLaDemandeExpire() {
        UserEntity traveler = persistUser("acct_" + UUID.randomUUID());
        UserEntity sender = persistUser(null);
        UUID bidId = persistAcceptedBid(persistAnnouncement(traveler.getId()), sender.getId());
        var supplement = supplementService.create(bidId, traveler.getFirebaseUid(), new CreateSupplementRequest(
                new BigDecimal("15"), BidSupplementReason.OTHER, null, null, "tracking/" + bidId + "/1.jpg"));
        jdbc.update("UPDATE bid_supplements SET expires_at = expires_at - interval '3 hours' WHERE id = ?",
                supplement.id());

        assertThat(trackingService.recordScan(new QrScanRequest(bidId, TrackingEventType.DEPART,
                null, null, null, null, null), traveler.getFirebaseUid()).created()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM bid_supplements WHERE id = ?", String.class,
                supplement.id())).isEqualTo("EXPIRED");
    }

    @org.junit.jupiter.api.Test
    void filet_surPostgres_expireLesEchuesEtAnnuleCellesDesColisTermines() {
        UserEntity traveler = persistUser("acct_" + UUID.randomUUID());
        UserEntity sender = persistUser(null);
        UUID expiringBid = persistAcceptedBid(persistAnnouncement(traveler.getId()), sender.getId());
        UUID endedBid = persistAcceptedBid(persistAnnouncement(traveler.getId()), sender.getId());
        var expiring = supplementService.create(expiringBid, traveler.getFirebaseUid(), new CreateSupplementRequest(
                new BigDecimal("15"), BidSupplementReason.OTHER, null, null, "tracking/" + expiringBid + "/1.jpg"));
        var ofEndedBid = supplementService.create(endedBid, traveler.getFirebaseUid(), new CreateSupplementRequest(
                new BigDecimal("15"), BidSupplementReason.OTHER, null, null, "tracking/" + endedBid + "/1.jpg"));
        jdbc.update("UPDATE bid_supplements SET expires_at = expires_at - interval '3 hours' WHERE id = ?", expiring.id());
        jdbc.update("UPDATE bids SET status = 'CANCELLED', updated_at = updated_at - interval '1 hour' WHERE id = ?",
                endedBid);

        BidSupplementReconciliationJob.Outcome outcome = reconciliationJob.run();

        assertThat(outcome.expired()).isGreaterThanOrEqualTo(1);
        assertThat(outcome.cancelled()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bid_supplements WHERE id = ?", String.class, expiring.id()))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM bid_supplements WHERE id = ?", String.class, ofEndedBid.id()))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT closed_reason FROM bid_supplements WHERE id = ?", String.class,
                ofEndedBid.id())).isEqualTo("BID_ENDED");
    }

    private UserEntity persistUser(String stripeAccountId) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("supplement-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.TRAVELER, Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        user.setStripeAccountId(stripeAccountId);
        return userRepository.saveAndFlush(user);
    }

    private UUID persistAnnouncement(UUID travelerId) {
        return tx.execute(status -> {
            AnnouncementEntity announcement = new AnnouncementEntity();
            announcement.setTravelerId(travelerId);
            announcement.setDepartureCity("Paris");
            announcement.setArrivalCity("Dakar");
            announcement.setDepartureDate(LocalDate.now().plusDays(10));
            announcement.setTransportMode(TransportMode.PLANE);
            announcement.setPickupAddressLabel("Gare du Nord, Paris");
            announcement.setPickupLat(new BigDecimal("48.880756"));
            announcement.setPickupLng(new BigDecimal("2.354987"));
            announcement.setDeliveryAddressLabel("Aéroport de Dakar");
            announcement.setDeliveryLat(new BigDecimal("14.670833"));
            announcement.setDeliveryLng(new BigDecimal("-17.073056"));
            announcement.setAvailableKg(new BigDecimal("20.00"));
            announcement.setTotalKg(new BigDecimal("23.00"));
            announcement.setPricePerKg(new BigDecimal("20.00"));
            announcement.setCurrency("EUR");
            announcement.setTimezone("Europe/Paris");
            announcement.setStatus(AnnouncementStatus.ACTIVE);
            announcement.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.STRIPE));
            entityManager.persist(announcement);
            return announcement.getId();
        });
    }

    private UUID persistAcceptedBid(UUID announcementId, UUID senderId) {
        return tx.execute(status -> {
            BidEntity bid = new BidEntity();
            bid.setAnnouncementId(announcementId);
            bid.setSenderId(senderId);
            bid.setPaymentMethod(PaymentMethod.STRIPE);
            bid.setCurrency("EUR");
            bid.setWeightKg(new BigDecimal("2"));
            bid.setStatus(BidStatus.ACCEPTED);
            bid.setQrToken("qr-" + UUID.randomUUID());
            bid.setTrackingNumber("DON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            entityManager.persist(bid);
            return bid.getId();
        });
    }
}
