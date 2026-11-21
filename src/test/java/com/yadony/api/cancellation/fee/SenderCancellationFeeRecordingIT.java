package com.yadony.api.cancellation.fee;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.CancellationEntity;
import com.yadony.api.cancellation.CancellationService;
import com.yadony.api.cancellation.NoShowArbitrationService;
import com.yadony.api.cancellation.NoShowContestationTimeoutJob;
import com.yadony.api.cancellation.RescheduleDecision;
import com.yadony.api.cancellation.RescheduleDecisionService;
import com.yadony.api.city.GeoNamesDataLoader;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Enregistrement de la retenue sur la vraie pile (PostgreSQL, migrations Flyway dont V393, vrais
 * services et vrais événements) : la ligne {@code DUE} naît dans la transaction de l'annulation, avant
 * le commit, pour que le remboursement ({@code AFTER_COMMIT}, asynchrone) la trouve ; elle disparaît
 * avec cette transaction si l'annulation est annulée. Seules les trois fautes de l'expéditeur la créent.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Retenue d'annulation de l'expéditeur : enregistrement (PostgreSQL)")
class SenderCancellationFeeRecordingIT {

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

    @Autowired BidService bidService;
    @Autowired CancellationService cancellationService;
    @Autowired NoShowArbitrationService noShowArbitrationService;
    @Autowired NoShowContestationTimeoutJob noShowContestationTimeoutJob;
    @Autowired RescheduleDecisionService rescheduleDecisionService;
    @Autowired SenderCancellationFeeRecorder recorder;
    @Autowired CancellationFeeRepository feeRepository;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

    @Test
    @DisplayName("expéditeur annule un colis accepté : ligne DUE dans la transaction de l'annulation, partie avec son rollback")
    void bidCancelledBySender_accepted_recordsDueInTheCancellationTransaction() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(traveler, OffsetDateTime.now(ZoneOffset.UTC).plusHours(36));
        BidEntity bid = persistBid(trip, sender, BidStatus.ACCEPTED, PaymentMethod.STRIPE);

        tx.executeWithoutResult(status -> {
            bidService.cancelBid(bid.getId(), sender.getFirebaseUid());
            // Avant le commit, dans la même transaction : la ligne existe déjà.
            assertThat(feeRepository.findByBidId(bid.getId()))
                    .hasValueSatisfying(f -> assertThat(f.getStatus()).isEqualTo(CancellationFeeStatus.DUE));
            status.setRollbackOnly();
        });
        // Annulation défaite : la retenue l'est aussi (ni REQUIRES_NEW ni AFTER_COMMIT).
        assertThat(feeRows(bid.getId())).isZero();
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.ACCEPTED);

        bidService.cancelBid(bid.getId(), sender.getFirebaseUid());

        Map<String, Object> row = feeRow(bid.getId());
        assertThat(row.get("status")).isEqualTo("DUE");
        assertThat(row.get("reason")).isEqualTo("SENDER_CANCEL");
        assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo("0.20");
        assertThat(row.get("sender_id")).isEqualTo(sender.getId());
        assertThat(row.get("traveler_id")).isEqualTo(traveler.getId());
        assertThat(row.get("currency")).isEqualTo("EUR");
        assertThat((BigDecimal) row.get("gross_fee")).isEqualByComparingTo("0");
        assertThat(auditCount(bid.getId(), SenderCancellationFeeRecorder.AUDIT_FEE_DUE)).isEqualTo(1);
    }

    @Test
    @DisplayName("voyageur annule un colis accepté : aucune retenue")
    void bidCancelledByTraveler_noFee() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        AnnouncementEntity trip = persistTrip(traveler, OffsetDateTime.now(ZoneOffset.UTC).plusHours(36));
        BidEntity bid = persistBid(trip, persistUser(Role.SENDER), BidStatus.ACCEPTED, PaymentMethod.STRIPE);

        bidService.cancelBid(bid.getId(), traveler.getFirebaseUid());

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(feeRows(bid.getId())).isZero();
    }

    @Test
    @DisplayName("expéditeur annule un colis payé mais pas encore accepté : aucune retenue")
    void bidCancelledBySender_paymentEscrowed_noFee() {
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(persistUser(Role.TRAVELER), OffsetDateTime.now(ZoneOffset.UTC).plusHours(12));
        BidEntity bid = persistBid(trip, sender, BidStatus.PAYMENT_ESCROWED, PaymentMethod.STRIPE);

        bidService.cancelBid(bid.getId(), sender.getFirebaseUid());

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(feeRows(bid.getId())).isZero();
    }

    @Test
    @DisplayName("expéditeur annule après la remise : retenue SENDER_CANCEL_AFTER_HANDOVER")
    void afterHandoverBySender_recordsDue() {
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(persistUser(Role.TRAVELER), OffsetDateTime.now(ZoneOffset.UTC).plusHours(12));
        BidEntity bid = persistBid(trip, sender, BidStatus.HANDED_OVER, PaymentMethod.MOBILE_MONEY);

        cancellationService.cancelAfterHandover(sender.getFirebaseUid(), bid.getId());

        Map<String, Object> row = feeRow(bid.getId());
        assertThat(row.get("reason")).isEqualTo("SENDER_CANCEL_AFTER_HANDOVER");
        assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo("0.30");
        assertThat(row.get("status")).isEqualTo("DUE");
    }

    @Test
    @DisplayName("voyageur annule après la remise : aucune retenue")
    void afterHandoverByTraveler_noFee() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        AnnouncementEntity trip = persistTrip(traveler, OffsetDateTime.now(ZoneOffset.UTC).plusHours(12));
        BidEntity bid = persistBid(trip, persistUser(Role.SENDER), BidStatus.HANDED_OVER, PaymentMethod.STRIPE);

        cancellationService.cancelAfterHandover(traveler.getFirebaseUid(), bid.getId());

        assertThat(feeRows(bid.getId())).isZero();
    }

    @Test
    @DisplayName("absence de l'expéditeur confirmée par lui-même : retenue SENDER_NO_SHOW ; en attente : rien")
    void senderNoShowConfirmed_recordsDue() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        BidEntity bid = noShowReported(traveler, sender);
        assertThat(feeRows(bid.getId())).isZero();

        cancellationService.confirmSenderNoShow(bid.getId(), sender.getId());

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.CANCELLED);
        Map<String, Object> row = feeRow(bid.getId());
        assertThat(row.get("reason")).isEqualTo("SENDER_NO_SHOW");
        assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo("0.30");
    }

    @Test
    @DisplayName("absence de l'expéditeur confirmée par l'équipe : retenue SENDER_NO_SHOW")
    void senderNoShowConfirmedByAdmin_recordsDue() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = noShowReported(traveler, persistUser(Role.SENDER));

        noShowArbitrationService.confirm(cancellationId(bid.getId()), persistUser(Role.ADMIN).getId(), "Absent");

        assertThat(feeRow(bid.getId()).get("reason")).isEqualTo("SENDER_NO_SHOW");
    }

    @Test
    @DisplayName("absence de l'expéditeur confirmée à l'échéance de contestation : retenue SENDER_NO_SHOW")
    void senderNoShowConfirmedByTimeout_recordsDue() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = noShowReported(traveler, persistUser(Role.SENDER));
        jdbc.update("UPDATE cancellations SET contestation_deadline = now() - interval '1 hour' WHERE bid_id = ?",
                bid.getId());

        noShowContestationTimeoutJob.run();

        assertThat(feeRow(bid.getId()).get("reason")).isEqualTo("SENDER_NO_SHOW");
    }

    @Test
    @DisplayName("absence de l'expéditeur rejetée par l'équipe : aucune retenue")
    void senderNoShowRejectedByAdmin_noFee() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = noShowReported(traveler, persistUser(Role.SENDER));

        noShowArbitrationService.reject(cancellationId(bid.getId()), persistUser(Role.ADMIN).getId(), "Venu");

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.ACCEPTED);
        assertThat(feeRows(bid.getId())).isZero();
    }

    @Test
    @DisplayName("retrait de l'expéditeur après un report du trajet : aucune retenue")
    void tripRescheduleWithdrawn_noFee() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(traveler, OffsetDateTime.now(ZoneOffset.UTC).plusHours(30));
        BidEntity bid = persistBid(trip, sender, BidStatus.ACCEPTED, PaymentMethod.STRIPE);
        UUID rescheduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trip_reschedules (id, announcement_id, traveler_id, reason, previous_departure_date,
                                              new_departure_date, created_at)
                VALUES (?, ?, ?, 'POSTPONED', ?, ?, now())
                """, rescheduleId, trip.getId(), traveler.getId(), LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2));
        jdbc.update("UPDATE bids SET pending_reschedule_id = ?, handover_deadline = ? WHERE id = ?", rescheduleId,
                LocalDateTime.now(ZoneOffset.UTC).plusHours(6), bid.getId());

        rescheduleDecisionService.decide(sender.getFirebaseUid(), bid.getId(), RescheduleDecision.WITHDRAW);

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(feeRows(bid.getId())).isZero();
    }

    @Test
    @DisplayName("report du trajet en attente : l'annulation ordinaire par l'expéditeur ne porte aucune retenue")
    void tripReschedulePending_senderCancelsViaGenericPath_noFee() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(traveler, OffsetDateTime.now(ZoneOffset.UTC).plusHours(30));
        BidEntity bid = persistBid(trip, sender, BidStatus.ACCEPTED, PaymentMethod.STRIPE);
        pendingReschedule(trip, traveler, bid, LocalDateTime.now(ZoneOffset.UTC).plusHours(6));

        // PUT /bids/{id}/cancel au lieu du bouton « Me retirer » du report.
        bidService.cancelBid(bid.getId(), sender.getFirebaseUid());

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(feeRows(bid.getId())).isZero();
        assertThat(auditCount(bid.getId(), SenderCancellationFeeRecorder.AUDIT_FEE_DUE)).isZero();
    }

    @Test
    @DisplayName("report du trajet dont la réponse a expiré : l'annulation par l'expéditeur porte la retenue")
    void tripRescheduleDecisionExpired_senderCancels_recordsDue() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(traveler, OffsetDateTime.now(ZoneOffset.UTC).plusHours(30));
        BidEntity bid = persistBid(trip, sender, BidStatus.ACCEPTED, PaymentMethod.STRIPE);
        pendingReschedule(trip, traveler, bid, LocalDateTime.now(ZoneOffset.UTC).minusHours(1));

        bidService.cancelBid(bid.getId(), sender.getFirebaseUid());

        Map<String, Object> row = feeRow(bid.getId());
        assertThat(row.get("status")).isEqualTo("DUE");
        assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo("0.20");
    }

    @Test
    @DisplayName("deux enregistrements pour le même colis : une seule ligne ; jamais hors d'une transaction")
    void recordTwice_singleRow() {
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(persistUser(Role.TRAVELER), OffsetDateTime.now(ZoneOffset.UTC).plusHours(36));
        BidEntity bid = persistBid(trip, sender, BidStatus.CANCELLED, PaymentMethod.STRIPE);

        tx.executeWithoutResult(s -> recorder.record(bid.getId(), CancellationFeeReason.SENDER_CANCEL));
        tx.executeWithoutResult(s -> recorder.record(bid.getId(), CancellationFeeReason.SENDER_NO_SHOW));

        assertThat(feeRows(bid.getId())).isEqualTo(1);
        assertThat(feeRow(bid.getId()).get("reason")).isEqualTo("SENDER_CANCEL");
        assertThat(auditCount(bid.getId(), SenderCancellationFeeRecorder.AUDIT_FEE_DUE)).isEqualTo(1);
        // Hors transaction, la retenue survivrait à une annulation défaite : refusé.
        assertThatThrownBy(() -> recorder.record(bid.getId(), CancellationFeeReason.SENDER_CANCEL))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // --- helpers ---

    /** Colis accepté, carte, remise dépassée, départ dans 12 h ; déclaration « expéditeur absent » ouverte. */
    private BidEntity noShowReported(UserEntity traveler, UserEntity sender) {
        AnnouncementEntity trip = persistTrip(traveler, OffsetDateTime.now(ZoneOffset.UTC).plusHours(12));
        BidEntity bid = persistBid(trip, sender, BidStatus.ACCEPTED, PaymentMethod.STRIPE);
        jdbc.update("UPDATE bids SET handover_deadline = ? WHERE id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(2), bid.getId());
        CancellationEntity reported = cancellationService.reportSenderNoShow(bid.getId(), traveler.getId());
        assertThat(reported.getId()).isNotNull();
        return bid;
    }

    /** Report du trajet posé sur le colis, réponse attendue jusqu'à {@code decisionDeadline} (date de remise). */
    private void pendingReschedule(AnnouncementEntity trip, UserEntity traveler, BidEntity bid,
                                   LocalDateTime decisionDeadline) {
        UUID rescheduleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trip_reschedules (id, announcement_id, traveler_id, reason, previous_departure_date,
                                              new_departure_date, created_at)
                VALUES (?, ?, ?, 'POSTPONED', ?, ?, now())
                """, rescheduleId, trip.getId(), traveler.getId(), LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2));
        jdbc.update("UPDATE bids SET pending_reschedule_id = ?, handover_deadline = ? WHERE id = ?", rescheduleId,
                decisionDeadline, bid.getId());
    }

    private UUID cancellationId(UUID bidId) {
        return jdbc.queryForObject("SELECT id FROM cancellations WHERE bid_id = ?", UUID.class, bidId);
    }

    private long feeRows(UUID bidId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cancellation_fees WHERE bid_id = ?", Long.class, bidId);
        return n == null ? 0 : n;
    }

    private Map<String, Object> feeRow(UUID bidId) {
        return jdbc.queryForMap("SELECT * FROM cancellation_fees WHERE bid_id = ?", bidId);
    }

    private long auditCount(UUID entityId, String action) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Long.class, entityId, action);
        return n == null ? 0 : n;
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("fee-recording-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private AnnouncementEntity persistTrip(UserEntity traveler, OffsetDateTime departureAt) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(departureAt.toLocalDate());
        a.setDepartureAt(departureAt);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport de Dakar");
        a.setDeliveryLat(new BigDecimal("14.670833"));
        a.setDeliveryLng(new BigDecimal("-17.073056"));
        a.setAvailableKg(new BigDecimal("15.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("10.00"));
        a.setCurrency("EUR");
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.saveAndFlush(a);
    }

    private BidEntity persistBid(AnnouncementEntity trip, UserEntity sender, BidStatus status, PaymentMethod method) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setCurrency("EUR");
        bid.setPaymentMethod(method);
        bid.setStatus(status);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        return bidRepository.saveAndFlush(bid);
    }
}
