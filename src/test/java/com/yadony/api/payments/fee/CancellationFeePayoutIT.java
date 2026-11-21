package com.yadony.api.payments.fee;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.fee.CancellationFeeEntity;
import com.yadony.api.cancellation.fee.CancellationFeeReason;
import com.yadony.api.cancellation.fee.CancellationFeeRepository;
import com.yadony.api.cancellation.fee.CancellationFeeStatus;
import com.yadony.api.city.GeoNamesDataLoader;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.RefundProcessor;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Versement du dédommagement d'annulation au voyageur sur la vraie pile : remboursement partiel réel
 * ({@link RefundProcessor}, transaction REQUIRES_NEW), événement {@link CancellationFeeRetainedEvent} écouté après
 * commit, vrai port {@code cancellation} et son verrou de ligne, vrai audit, vraies alertes admin, planificateur.
 * PostgreSQL (Flyway jusqu'à V390) ; seul Stripe est simulé.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Versement du dédommagement d'annulation au voyageur (PostgreSQL, Stripe simulé)")
class CancellationFeePayoutIT {

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

    @Autowired RefundProcessor refundProcessor;
    @Autowired CancellationFeePayoutScheduler scheduler;
    @Autowired PaymentRepository paymentRepository;
    @Autowired CancellationFeeRepository feeRepository;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

    private UUID travelerId;
    private UUID bidId;
    private UUID paymentId;
    private UUID feeId;
    private String piId;

    @BeforeEach
    void setUp() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        travelerId = traveler.getId();
        UserEntity sender = persistUser(Role.SENDER);
        bidId = persistCancelledBid(persistTrip(traveler), sender).getId();
        piId = "pi_payout_it_" + UUID.randomUUID();
        paymentId = persistEscrow();
        feeId = persistDueFee(sender);
    }

    @Test
    @DisplayName("retenue réglée : un seul Transfer après le commit du remboursement, ligne PAID_OUT, rejeu sans effet")
    void retainedFee_paidOnceAfterCommit_replayHasNoEffect() {
        connectAccount();
        List<String> statesSeenByStripe = new ArrayList<>();
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            stubRefund(piStatic, refundStatic);
            stubNoExistingTransfer(transferStatic);
            ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            transferStatic.when(() -> Transfer.create(params.capture(), options.capture())).thenAnswer(call -> {
                // Lu par une autre lecture que celle du remboursement : il est déjà commité.
                statesSeenByStripe.add(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId)
                        + "/" + jdbc.queryForObject("SELECT status FROM cancellation_fees WHERE id = ?", String.class, feeId));
                return transfer("tr_it");
            });

            assertThat(refund()).isTrue();

            transferStatic.verify(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)), times(1));
            assertThat(statesSeenByStripe).containsExactly("REFUNDED/RETAINED");
            TransferCreateParams sent = params.getValue();
            assertThat(sent.getAmount()).isEqualTo(2684L);
            assertThat(sent.getCurrency()).isEqualTo("eur");
            assertThat(sent.getDestination()).isEqualTo(accountId());
            assertThat(sent.getSourceTransaction()).isEqualTo("ch_it");
            assertThat(sent.getMetadata())
                    .containsEntry("bid_id", bidId.toString())
                    .containsEntry("fee_id", feeId.toString())
                    .containsEntry("payment_id", paymentId.toString());
            assertThat(options.getValue().getIdempotencyKey()).isEqualTo("cancel-fee-transfer-" + feeId);

            // Rejeu de l'événement (après commit) puis passage du planificateur : la ligne est versée, rien ne repart.
            tx.executeWithoutResult(status -> events.publishEvent(new CancellationFeeRetainedEvent(bidId)));
            assertThat(scheduler.run()).isZero();
            transferStatic.verify(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)), times(1));
        }

        CancellationFeeEntity fee = feeRepository.findById(feeId).orElseThrow();
        assertThat(fee.getStatus()).isEqualTo(CancellationFeeStatus.PAID_OUT);
        assertThat(fee.getPayoutRef()).isEqualTo("tr_it");
        assertThat(fee.getTravelerNet()).isEqualByComparingTo("26.84");
        assertThat(audits("CANCELLATION_FEE_PAID_OUT")).isEqualTo(1);
        assertThat(audits("CANCELLATION_FEE_PAYOUT_PENDING")).isZero();
        assertThat(openAlerts()).isZero();
    }

    @Test
    @DisplayName("voyageur sans compte Connect : en attente, une seule alerte, puis versé par le planificateur")
    void noConnectAccount_pendingWithOneAlert_thenPaidByTheScheduler() {
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            stubRefund(piStatic, refundStatic);

            assertThat(refund()).isTrue();

            assertThat(status()).isEqualTo(CancellationFeeStatus.PAYOUT_PENDING);
            assertThat(openAlerts()).isEqualTo(1);
            assertThat(audits("CANCELLATION_FEE_PAYOUT_PENDING")).isEqualTo(1);
            transferStatic.verifyNoInteractions();

            // Passage suivant, toujours sans compte : rien ne part, aucune seconde alerte ni second audit.
            assertThat(scheduler.run()).isZero();
            assertThat(status()).isEqualTo(CancellationFeeStatus.PAYOUT_PENDING);
            assertThat(openAlerts()).isEqualTo(1);
            assertThat(audits("CANCELLATION_FEE_PAYOUT_PENDING")).isEqualTo(1);
            transferStatic.verifyNoInteractions();

            connectAccount();
            stubNoExistingTransfer(transferStatic);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            transferStatic.when(() -> Transfer.create(any(TransferCreateParams.class), options.capture()))
                    .thenReturn(transfer("tr_later"));

            assertThat(scheduler.run()).isEqualTo(1);

            assertThat(options.getAllValues()).extracting(RequestOptions::getIdempotencyKey)
                    .containsExactly("cancel-fee-transfer-" + feeId);
        }

        CancellationFeeEntity fee = feeRepository.findById(feeId).orElseThrow();
        assertThat(fee.getStatus()).isEqualTo(CancellationFeeStatus.PAID_OUT);
        assertThat(fee.getPayoutRef()).isEqualTo("tr_later");
        assertThat(audits("CANCELLATION_FEE_PAID_OUT")).isEqualTo(1);
        assertThat(openAlerts()).as("alerte close une fois versé").isZero();
    }

    @Test
    @DisplayName("échec Stripe : en attente, puis le planificateur reprend avec la même clé d'idempotence")
    void stripeFailure_thenSchedulerRetry_sameIdempotencyKey() {
        connectAccount();
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            stubRefund(piStatic, refundStatic);
            stubNoExistingTransfer(transferStatic);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            transferStatic.when(() -> Transfer.create(any(TransferCreateParams.class), options.capture()))
                    .thenThrow(new ApiConnectionException("timeout"))
                    .thenReturn(transfer("tr_retry"));

            assertThat(refund()).isTrue();
            assertThat(status()).isEqualTo(CancellationFeeStatus.PAYOUT_PENDING);
            assertThat(openAlerts()).isEqualTo(1);

            assertThat(scheduler.run()).isEqualTo(1);

            assertThat(options.getAllValues()).extracting(RequestOptions::getIdempotencyKey)
                    .containsExactly("cancel-fee-transfer-" + feeId, "cancel-fee-transfer-" + feeId);
        }
        assertThat(status()).isEqualTo(CancellationFeeStatus.PAID_OUT);
        assertThat(openAlerts()).isZero();
    }

    @Test
    @DisplayName("écouteur perdu : une ligne RETAINED de plus de 10 min est versée par le planificateur, pas avant")
    void lostListener_retainedRowOlderThanTenMinutes_paidByTheScheduler() {
        connectAccount();
        // Remboursement commité, écouteur jamais passé (redéploiement) : la ligne est restée RETAINED.
        jdbc.update("UPDATE payments SET status = 'REFUNDED', refunded_amount = 70.00 WHERE id = ?", paymentId);
        jdbc.update("""
                UPDATE cancellation_fees SET status = 'RETAINED', gross_fee = 30.00, refund_fees = 1.75,
                       commission = 1.41, traveler_net = 26.84 WHERE id = ?""", feeId);
        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            stubNoExistingTransfer(transferStatic);
            transferStatic.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(transfer("tr_sweep"));

            assertThat(scheduler.run()).as("réglée à l'instant : l'écouteur a encore la main").isZero();
            transferStatic.verify(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)), never());
            assertThat(status()).isEqualTo(CancellationFeeStatus.RETAINED);

            // Écrite comme l'entité l'aurait écrite il y a 11 min (LocalDateTime UTC, même conversion de fuseau
            // que Hibernate) : un now() SQL serait décalé du fuseau de la JVM par rapport aux valeurs de l'appli.
            jdbc.update("UPDATE cancellation_fees SET updated_at = ? WHERE id = ?",
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11), feeId);

            assertThat(scheduler.run()).isEqualTo(1);
            transferStatic.verify(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)), times(1));
        }
        assertThat(status()).isEqualTo(CancellationFeeStatus.PAID_OUT);
        assertThat(feeRepository.findById(feeId).orElseThrow().getPayoutRef()).isEqualTo("tr_sweep");
    }

    private boolean refund() {
        return refundProcessor.processRefund(paymentId, "PAYMENT_REFUNDED_BID_REJECTED", bidId,
                Map.of("reason", "bid_rejected"));
    }

    private CancellationFeeStatus status() {
        return feeRepository.findById(feeId).orElseThrow().getStatus();
    }

    /** Compte Connect propre au voyageur du test ({@code users.stripe_account_id} est unique). */
    private String accountId() {
        return "acct_it_" + travelerId;
    }

    private void connectAccount() {
        UserEntity traveler = userRepository.findById(travelerId).orElseThrow();
        traveler.setStripeAccountId(accountId());
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        userRepository.saveAndFlush(traveler);
    }

    /** PaymentIntent capturé (frais Stripe réels 1,75 €) et remboursement partiel accepté. */
    private void stubRefund(MockedStatic<PaymentIntent> piStatic, MockedStatic<Refund> refundStatic) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        BalanceTransaction balance = mock(BalanceTransaction.class);
        when(balance.getFee()).thenReturn(175L);
        when(balance.getCurrency()).thenReturn("eur");
        Charge charge = mock(Charge.class);
        when(charge.getBalanceTransactionObject()).thenReturn(balance);
        PaymentIntent expanded = mock(PaymentIntent.class);
        when(expanded.getLatestChargeObject()).thenReturn(charge);
        piStatic.when(() -> PaymentIntent.retrieve(piId)).thenReturn(pi);
        piStatic.when(() -> PaymentIntent.retrieve(eq(piId), any(PaymentIntentRetrieveParams.class),
                (RequestOptions) any())).thenReturn(expanded);
        refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(mock(Refund.class));
    }

    private static void stubNoExistingTransfer(MockedStatic<Transfer> transferStatic) {
        TransferCollection none = new TransferCollection();
        none.setData(List.of());
        transferStatic.when(() -> Transfer.list(any(TransferListParams.class))).thenReturn(none);
    }

    /** Objet Stripe réel : un mock créé pendant un stub statique laisserait ce stub inachevé. */
    private static Transfer transfer(String id) {
        Transfer t = new Transfer();
        t.setId(id);
        return t;
    }

    private long audits(String action) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Long.class, bidId, action);
        return n == null ? 0 : n;
    }

    private long openAlerts() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM admin_alerts WHERE type = ? AND resolved = false",
                Long.class, CancellationFeePayoutService.ALERT_PREFIX + feeId);
        return n == null ? 0 : n;
    }

    private UUID persistEscrow() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setStripePaymentIntentId(piId);
        p.setStripeChargeId("ch_it");
        p.setAmount(new BigDecimal("100.00"));
        p.setCommissionAmount(new BigDecimal("4.76"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        return paymentRepository.saveAndFlush(p).getId();
    }

    private UUID persistDueFee(UserEntity sender) {
        CancellationFeeEntity fee = new CancellationFeeEntity();
        fee.setBidId(bidId);
        fee.setSenderId(sender.getId());
        fee.setTravelerId(travelerId);
        fee.setRate(new BigDecimal("0.30"));
        fee.setReason(CancellationFeeReason.SENDER_CANCEL);
        fee.setStatus(CancellationFeeStatus.DUE);
        fee.setGrossFee(BigDecimal.ZERO);
        fee.setRefundFees(BigDecimal.ZERO);
        fee.setCommission(BigDecimal.ZERO);
        fee.setTravelerNet(BigDecimal.ZERO);
        fee.setCurrency("EUR");
        return feeRepository.saveAndFlush(fee).getId();
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("fee-payout-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private AnnouncementEntity persistTrip(UserEntity traveler) {
        OffsetDateTime departureAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(12);
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

    private BidEntity persistCancelledBid(AnnouncementEntity trip, UserEntity sender) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setCurrency("EUR");
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setStatus(BidStatus.CANCELLED);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        return bidRepository.saveAndFlush(bid);
    }
}
