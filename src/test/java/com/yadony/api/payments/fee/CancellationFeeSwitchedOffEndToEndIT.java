package com.yadony.api.payments.fee;

import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
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
import com.yadony.api.matching.BidService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.BidRejectedEventListener;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.RefundProcessor;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Frais d'annulation ÉTEINTS (défaut de production, profil e2e partagé sans le profil {@code cancellation-fee}) : un
 * expéditeur qui annule à J-1 un colis accepté et payé par carte (PaymentIntent capturé) est remboursé EN ENTIER,
 * exactement comme avant la fonction. Aucune ligne {@code cancellation_fees}, aucun audit ni notification de retenue.
 *
 * <p>Vraie annulation ({@code BidService.cancelBid}), vraie décision de la retenue, vrai {@link BidRejectedEvent} puis
 * vrai code de l'écouteur de remboursement et vrai {@link RefundProcessor}, sur PostgreSQL. Seul Stripe est simulé. Ses
 * mocks statiques ne valent que sur le fil du test : l'écouteur asynchrone du contexte est remplacé, et son code est
 * rejoué ici, sur ce fil, avec l'événement réellement publié par l'annulation.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@RecordApplicationEvents
@DisplayName("Frais d'annulation éteints : remboursement intégral comme avant la fonction (PostgreSQL, Stripe simulé)")
class CancellationFeeSwitchedOffEndToEndIT {

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
    @Autowired RefundProcessor refundProcessor;
    @Autowired PaymentRepository paymentRepository;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents applicationEvents;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Remboursement asynchrone remplacé : rejoué sur le fil du test, où Stripe est simulé.
    @MockitoBean BidRejectedEventListener asyncRefundListener;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

    @Test
    @DisplayName("expéditeur annule à J-1 un colis carte capturé : Refund.create sans montant, aucune retenue")
    void senderCancelsAtDayMinusOne_capturedCard_fullRefund_noFeeAnywhere() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        // Départ dans 20 h : 30 % si la fonction était allumée.
        AnnouncementEntity trip = persistTrip(traveler, OffsetDateTime.now(ZoneOffset.UTC).plusHours(20));
        BidEntity bid = persistAcceptedCardBid(trip, sender);
        String piId = "pi_fee_off_it_" + UUID.randomUUID();
        UUID paymentId = persistCapturedEscrow(bid, piId);

        bidService.cancelBid(bid.getId(), sender.getFirebaseUid());

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(feeRows(bid.getId())).isZero();
        BidRejectedEvent cancelled = applicationEvents.stream(BidRejectedEvent.class)
                .filter(e -> e.getBidId().equals(bid.getId()))
                .findFirst().orElseThrow();

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = mock(PaymentIntent.class);
            when(captured.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(piId)).thenReturn(captured);
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            refundStatic.when(() -> Refund.create(params.capture(), options.capture())).thenReturn(mock(Refund.class));

            new BidRejectedEventListener(paymentRepository, refundProcessor).handleBidRejected(cancelled);

            assertThat(params.getValue().getPaymentIntent()).isEqualTo(piId);
            assertThat(params.getValue().getAmount()).as("remboursement intégral, sans montant").isNull();
            assertThat(options.getValue().getIdempotencyKey()).isEqualTo("refund-" + paymentId);
        }

        Map<String, Object> payment = jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?",
                paymentId);
        assertThat(payment.get("status")).isEqualTo("REFUNDED");
        assertThat(payment.get("refunded_amount")).isNull();
        assertThat(feeRows(bid.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action LIKE 'CANCELLATION_FEE%'",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?", Long.class,
                paymentId, "PAYMENT_REFUNDED_BID_REJECTED")).isEqualTo(1L);
        assertThat(applicationEvents.stream(CancellationFeeRetainedEvent.class)).isEmpty();
        verify(notificationDispatcher, after(1_000).never()).notifyUser(any(), anyString(), anyString(),
                argThat(data -> data != null && String.valueOf(data.get("type")).startsWith("CANCELLATION_FEE")));
    }

    private long feeRows(UUID bidId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM cancellation_fees WHERE bid_id = ?", Long.class, bidId);
        return n == null ? 0 : n;
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("fee-off-it-" + UUID.randomUUID());
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

    private BidEntity persistAcceptedCardBid(AnnouncementEntity trip, UserEntity sender) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setCurrency("EUR");
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        return bidRepository.saveAndFlush(bid);
    }

    /** Séquestre carte capturé à l'acceptation (modèle separate charges and transfers). */
    private UUID persistCapturedEscrow(BidEntity bid, String piId) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setStripePaymentIntentId(piId);
        p.setAmount(new BigDecimal("100.00"));
        p.setCommissionAmount(new BigDecimal("4.76"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        p.setCapturedAt(Instant.now());
        return paymentRepository.saveAndFlush(p).getId();
    }
}
