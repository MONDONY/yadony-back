package com.yadony.api.payments;

import com.stripe.exception.ApiException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
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
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.fee.CancellationFeePayoutService;
import com.yadony.api.payments.fee.CancellationFeeRetainedEvent;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Retenue d'annulation réglée au remboursement, sur la vraie pile (PostgreSQL, Flyway jusqu'à V390,
 * transaction REQUIRES_NEW de {@link RefundProcessor}, vrai port {@code cancellation} et son verrou de
 * ligne, vrai audit) : seul Stripe est simulé. Prouve ce que les tests unitaires ne peuvent pas : un échec
 * Stripe défait le claim ET laisse la retenue due, et l'entité paiement chargée avant le claim n'écrase
 * jamais le statut REFUNDED au flush.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@RecordApplicationEvents
@DisplayName("Remboursement moins la retenue d'annulation (PostgreSQL, Stripe simulé)")
class RefundProcessorCancellationFeeIT {

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
    @Autowired com.yadony.api.cancellation.fee.CancellationFeeAdminService adminService;
    @Autowired PaymentRepository paymentRepository;
    @Autowired CancellationFeeRepository feeRepository;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents applicationEvents;
    @Autowired PaymentService paymentService;
    @Autowired TransactionTemplate tx;
    @Autowired com.yadony.api.cancellation.fee.CancellationFeePreviewService previewService;
    @Autowired com.yadony.api.cancellation.fee.SenderCancellationFeeRecorder recorder;
    @MockitoBean NotificationDispatcher notificationDispatcher;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;
    // Le versement au voyageur qui suit le règlement a son propre IT (CancellationFeePayoutIT) : ici, la ligne
    // doit rester telle que le remboursement la laisse (RETAINED).
    @MockitoBean CancellationFeePayoutService payoutService;

    private String piId;
    private UUID bidId;
    private UUID paymentId;

    @BeforeEach
    void setUp() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(traveler);
        bidId = persistCancelledBid(trip, sender).getId();
        piId = "pi_fee_it_" + UUID.randomUUID();
        paymentId = persistEscrow();
        persistDueFee(sender, traveler);
    }

    @Test
    @DisplayName("PI capturé + retenue due : 70 € rendus, paiement REFUNDED, retenue RETAINED, événement publié")
    void dueFee_partialRefund_refundedPaymentAndRetainedFee() {
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            stubIntent(piStatic, "succeeded");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            assertThat(params.getValue().getAmount()).isEqualTo(7000L);
        }

        Map<String, Object> payment = jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?", paymentId);
        assertThat(payment.get("status")).isEqualTo("REFUNDED");
        assertThat((BigDecimal) payment.get("refunded_amount")).isEqualByComparingTo("70.00");

        CancellationFeeEntity fee = feeRepository.findByBidId(bidId).orElseThrow();
        assertThat(fee.getStatus()).isEqualTo(CancellationFeeStatus.RETAINED);
        assertThat(fee.getGrossFee()).isEqualByComparingTo("30.00");
        assertThat(fee.getRefundFees()).isEqualByComparingTo("1.75");
        assertThat(fee.getCommission()).isEqualByComparingTo("1.41");
        assertThat(fee.getTravelerNet()).isEqualByComparingTo("26.84");
        assertThat(audits(bidId, "CANCELLATION_FEE_RETAINED")).isEqualTo(1);
        assertThat(audits(paymentId, "PAYMENT_REFUNDED_BID_REJECTED")).isEqualTo(1);
        assertThat(applicationEvents.stream(CancellationFeeRetainedEvent.class))
                .containsExactly(new CancellationFeeRetainedEvent(bidId));

        // Rejeu de l'événement de remboursement : le claim est perdu, rien ne repart.
        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            assertThat(refund()).isFalse();
            refundStatic.verifyNoInteractions();
        }
        assertThat(audits(bidId, "CANCELLATION_FEE_RETAINED")).isEqualTo(1);
    }

    @Test
    @DisplayName("échec Stripe : claim défait (ESCROW), retenue toujours DUE, rien de réglé")
    void stripeFailure_rollsBackTheClaimAndKeepsTheFeeDue() {
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            stubIntent(piStatic, "succeeded");
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiException("boom", null, null, 500, null));

            assertThatThrownBy(this::refund).isInstanceOf(IllegalStateException.class);
        }

        Map<String, Object> payment = jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?", paymentId);
        assertThat(payment.get("status")).isEqualTo("ESCROW");
        assertThat(payment.get("refunded_amount")).isNull();
        assertThat(feeRepository.findByBidId(bidId).orElseThrow().getStatus()).isEqualTo(CancellationFeeStatus.DUE);
        assertThat(audits(bidId, "CANCELLATION_FEE_RETAINED")).isZero();
        assertThat(applicationEvents.stream(CancellationFeeRetainedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("autorisation non capturée : annulée en entier, retenue close NOT_APPLICABLE et auditée")
    void uncapturedIntent_cancelledWhole_feeClosedNotApplicable() throws Exception {
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent authorized = stubIntent(piStatic, "requires_capture");
            when(authorized.cancel(any(PaymentIntentCancelParams.class))).thenReturn(authorized);

            assertThat(refund()).isTrue();
        }

        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo("REFUNDED");
        assertThat(feeRepository.findByBidId(bidId).orElseThrow().getStatus())
                .isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        assertThat(audits(bidId, "CANCELLATION_FEE_NOT_APPLICABLE")).isEqualTo(1);
        assertThat(applicationEvents.stream(CancellationFeeRetainedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("l'admin renonce à la retenue due : le remboursement suivant est intégral chez Stripe")
    void adminWaivedTheDueFee_nextRefundIsAFullStripeRefund() {
        UUID adminId = UUID.randomUUID();
        adminService.waive(bidId, adminId, "Retenue contestée par l'expéditeur, accord du support");
        assertThat(feeRepository.findByBidId(bidId).orElseThrow().getStatus())
                .isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            stubIntent(piStatic, "succeeded");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            assertThat(params.getValue().getAmount()).as("remboursement intégral").isNull();
        }

        Map<String, Object> payment = jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?", paymentId);
        assertThat(payment.get("status")).isEqualTo("REFUNDED");
        assertThat(payment.get("refunded_amount")).isNull();
        assertThat(feeRepository.findByBidId(bidId).orElseThrow().getStatus())
                .isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM audit_log WHERE entity_id = ? AND action = ?",
                String.class, bidId, "CANCELLATION_FEE_NOT_APPLICABLE")).contains("ADMIN_WAIVED");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE entity_id = ? AND action = ?",
                UUID.class, bidId, "CANCELLATION_FEE_NOT_APPLICABLE")).isEqualTo(adminId);
        assertThat(audits(bidId, "CANCELLATION_FEE_RETAINED")).isZero();
        assertThat(applicationEvents.stream(CancellationFeeRetainedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("calcul impossible (taux invalide) : remboursé en entier, retenue close NOT_APPLICABLE auditée, alerte")
    void computationFailure_refundsFull_feeClosedNotApplicableWithAuditAndAlert() {
        // Barème non validé : un taux de 150 % passe NUMERIC(5,4) mais fait lever le calcul.
        jdbc.update("UPDATE cancellation_fees SET rate = 1.5 WHERE bid_id = ?", bidId);
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            stubIntent(piStatic, "succeeded");
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            assertThat(params.getValue().getAmount()).as("remboursement intégral").isNull();
        }

        Map<String, Object> payment = jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?", paymentId);
        assertThat(payment.get("status")).isEqualTo("REFUNDED");
        assertThat(payment.get("refunded_amount")).isNull();
        assertThat(feeRepository.findByBidId(bidId).orElseThrow().getStatus())
                .isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM audit_log WHERE entity_id = ? AND action = ?",
                String.class, bidId, "CANCELLATION_FEE_NOT_APPLICABLE")).contains("FEE_COMPUTATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_alerts WHERE type = ? AND resolved = false",
                Long.class, RefundProcessor.FEE_COMPUTE_FAILED_ALERT_PREFIX + paymentId)).isEqualTo(1L);
        assertThat(applicationEvents.stream(CancellationFeeRetainedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("webhook charge.refunded partiel pendant la transaction du remboursement : le paiement reste REFUNDED")
    void partialRefundWebhook_duringTheRefundTransaction_neverReopensTheEscrow() throws Exception {
        // Stripe envoie charge.refunded pendant que la transaction du remboursement (claim REFUNDED, verrou
        // de ligne) est encore ouverte : le webhook lit le paiement ESCROW, puis son écriture attend le
        // verrou. Elle ne doit pas réécrire ce statut périmé après le commit du remboursement.
        CountDownLatch refundSent = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> refundRun = pool.submit(() -> {
                try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
                     MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
                    stubIntent(piStatic, "succeeded");
                    refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                            .thenAnswer(call -> {
                                refundSent.countDown();
                                Thread.sleep(1_500);
                                return mock(Refund.class);
                            });
                    return refund();
                }
            });
            assertThat(refundSent.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> webhookRun = pool.submit(() -> tx.executeWithoutResult(status ->
                    paymentService.handleChargeRefunded(chargeRefunded(10_000L, 7_000L))));

            assertThat(refundRun.get(60, TimeUnit.SECONDS)).isTrue();
            webhookRun.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> payment = jdbc.queryForMap("SELECT status, refunded_amount FROM payments WHERE id = ?", paymentId);
        assertThat(payment.get("status")).isEqualTo("REFUNDED");
        assertThat((BigDecimal) payment.get("refunded_amount")).isEqualByComparingTo("70.00");
        assertThat(feeRepository.findByBidId(bidId).orElseThrow().getStatus()).isEqualTo(CancellationFeeStatus.RETAINED);
    }

    /**
     * Revue tâche 7, tour 2 : renonciation pendant le règlement. Le règlement tient le verrou de la retenue (lecture du
     * taux sous verrou) jusqu'à son commit : la renonciation attend, puis trouve la retenue RETAINED et répond 409.
     * Ordre des verrous : remboursement = paiement (claim) puis retenue ; renonciation = retenue seule.
     */
    @Test
    @DisplayName("renonciation pendant le règlement : elle attend le verrou puis reçoit 409, la retenue reste RETAINED")
    void waiveDuringTheSettlement_waitsForTheLock_thenConflicts() throws Exception {
        CountDownLatch refundSent = new CountDownLatch(1);
        CountDownLatch releaseRefund = new CountDownLatch(1);
        AtomicReference<RefundCreateParams> sent = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> refundRun = pool.submit(() -> {
                try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
                     MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
                    stubIntent(piStatic, "succeeded");
                    refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                            .thenAnswer(call -> {
                                sent.set(call.getArgument(0));
                                refundSent.countDown();
                                assertThat(releaseRefund.await(30, TimeUnit.SECONDS)).isTrue();
                                return mock(Refund.class);
                            });
                    return refund();
                }
            });
            assertThat(refundSent.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> waiveRun = pool.submit(() -> adminService.waive(bidId, UUID.randomUUID(),
                    "Renonciation pendant le règlement, test de concurrence"));

            Thread.sleep(1_500);
            assertThat(waiveRun.isDone()).as("la renonciation attend le verrou de la retenue").isFalse();
            releaseRefund.countDown();

            assertThat(refundRun.get(60, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiveRun.get(60, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.getErrorCode()).isEqualTo("cancellation-fee-not-due");
                    });
        } finally {
            releaseRefund.countDown();
            pool.shutdownNow();
        }

        assertThat(sent.get().getAmount()).as("remboursement partiel").isEqualTo(7000L);
        assertThat(feeRepository.findByBidId(bidId).orElseThrow().getStatus()).isEqualTo(CancellationFeeStatus.RETAINED);
        assertThat(audits(bidId, "CANCELLATION_FEE_RETAINED")).isEqualTo(1);
        assertThat(audits(bidId, "CANCELLATION_FEE_NOT_APPLICABLE")).isZero();
    }

    /**
     * Renonciation commitée pendant que le règlement attend le verrou : le règlement a lu « due » sans verrou avant la
     * renonciation, puis relit la ligne SOUS le verrou et voit NOT_APPLICABLE. Remboursement intégral, jamais RETAINED
     * par-dessus la renonciation (l'instance chargée par la première lecture est relue, pas réutilisée).
     */
    @Test
    @DisplayName("renonciation commitée pendant l'attente du règlement : remboursement intégral, retenue NOT_APPLICABLE")
    void waiveCommittedWhileTheSettlementWaits_fullRefund_neverRetainedOverTheWaiver() throws Exception {
        CountDownLatch waived = new CountDownLatch(1);
        CountDownLatch commitWaiver = new CountDownLatch(1);
        AtomicReference<RefundCreateParams> sent = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> waiveRun = pool.submit(() -> tx.executeWithoutResult(status -> {
                adminService.waive(bidId, UUID.randomUUID(), "Renonciation pendant le règlement, test de concurrence");
                waived.countDown();
                try {
                    assertThat(commitWaiver.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(waived.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> refundRun = pool.submit(() -> {
                try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
                     MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
                    stubIntent(piStatic, "succeeded");
                    refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                            .thenAnswer(call -> {
                                sent.set(call.getArgument(0));
                                return mock(Refund.class);
                            });
                    return refund();
                }
            });

            Thread.sleep(1_500);
            assertThat(refundRun.isDone()).as("le règlement attend le verrou de la retenue").isFalse();
            commitWaiver.countDown();

            waiveRun.get(60, TimeUnit.SECONDS);
            assertThat(refundRun.get(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            commitWaiver.countDown();
            pool.shutdownNow();
        }

        assertThat(sent.get().getAmount()).as("remboursement intégral").isNull();
        assertThat(feeRepository.findByBidId(bidId).orElseThrow().getStatus())
                .isEqualTo(CancellationFeeStatus.NOT_APPLICABLE);
        assertThat(audits(bidId, "CANCELLATION_FEE_RETAINED")).isZero();
        assertThat(audits(bidId, "CANCELLATION_FEE_NOT_APPLICABLE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payments WHERE id = ?", BigDecimal.class, paymentId))
                .isNull();
    }

    @Test
    @DisplayName("aperçu puis vrai règlement, carte EUR : l'expéditeur voit exactement le brut retenu et le remboursé")
    void preview_thenRealSettlement_card_sameGrossAndRefund() {
        UserEntity traveler = persistUser(Role.TRAVELER);
        UserEntity sender = persistUser(Role.SENDER);
        BidEntity bid = persistCancelledBid(persistTrip(traveler), sender); // départ dans 12 h : 30 %
        jdbc.update("UPDATE bids SET status = 'ACCEPTED' WHERE id = ?", bid.getId());
        String pi = "pi_fee_preview_it_" + UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setStripePaymentIntentId(pi);
        p.setAmount(new BigDecimal("80.00"));        // 80 × 30 % = 24 €, sous le plafond
        p.setCommissionAmount(new BigDecimal("3.81"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        p.setCapturedAt(java.time.Instant.now());
        UUID otherPaymentId = paymentRepository.saveAndFlush(p).getId();

        com.yadony.api.cancellation.dto.CancellationFeePreviewResponse preview =
                previewService.preview(sender.getFirebaseUid(), bid.getId());
        assertThat(preview.rate()).isEqualByComparingTo("0.30");
        assertThat(preview.fee()).isEqualByComparingTo("24.00");
        assertThat(preview.refundAmount()).isEqualByComparingTo("56.00");

        // Le vrai chemin : retenue enregistrée par la décision partagée, puis remboursement réglé par RefundProcessor.
        tx.executeWithoutResult(status -> recorder.record(bid.getId(), CancellationFeeReason.SENDER_CANCEL));
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            stubIntent(piStatic, "succeeded", pi);
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refundProcessor.processRefund(otherPaymentId, "PAYMENT_REFUNDED_BID_REJECTED", bid.getId(),
                    Map.of("reason", "bid_rejected"))).isTrue();

            assertThat(params.getValue().getAmount()).isEqualTo(5600L);
        }

        CancellationFeeEntity fee = feeRepository.findByBidId(bid.getId()).orElseThrow();
        assertThat(fee.getStatus()).isEqualTo(CancellationFeeStatus.RETAINED);
        assertThat(fee.getRate()).isEqualByComparingTo(preview.rate());
        assertThat(fee.getGrossFee()).isEqualByComparingTo(preview.fee());
        assertThat(fee.getCurrency()).isEqualTo(preview.currency());
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM payments WHERE id = ?", BigDecimal.class,
                otherPaymentId)).isEqualByComparingTo(preview.refundAmount());
    }

    /** Événement Stripe {@code charge.refunded} de ce paiement (montants en unités mineures). */
    private Event chargeRefunded(long amount, long amountRefunded) {
        Charge charge = mock(Charge.class);
        when(charge.getPaymentIntent()).thenReturn(piId);
        when(charge.getAmount()).thenReturn(amount);
        when(charge.getAmountRefunded()).thenReturn(amountRefunded);
        EventDataObjectDeserializer data = mock(EventDataObjectDeserializer.class);
        when(data.getObject()).thenReturn(Optional.of(charge));
        Event event = mock(Event.class);
        when(event.getDataObjectDeserializer()).thenReturn(data);
        return event;
    }

    private boolean refund() {
        return refundProcessor.processRefund(paymentId, "PAYMENT_REFUNDED_BID_REJECTED", bidId,
                Map.of("reason", "bid_rejected"));
    }

    /** PaymentIntent lu par le remboursement, et sa charge (frais Stripe réels 1,75 €) lue par {@code StripeFeeSource}. */
    private PaymentIntent stubIntent(MockedStatic<PaymentIntent> piStatic, String status) {
        return stubIntent(piStatic, status, piId);
    }

    private PaymentIntent stubIntent(MockedStatic<PaymentIntent> piStatic, String status, String piId) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn(status);
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
        return pi;
    }

    private UUID persistEscrow() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setStripePaymentIntentId(piId);
        p.setAmount(new BigDecimal("100.00"));
        p.setCommissionAmount(new BigDecimal("4.76"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        return paymentRepository.saveAndFlush(p).getId();
    }

    private void persistDueFee(UserEntity sender, UserEntity traveler) {
        CancellationFeeEntity fee = new CancellationFeeEntity();
        fee.setBidId(bidId);
        fee.setSenderId(sender.getId());
        fee.setTravelerId(traveler.getId());
        fee.setRate(new BigDecimal("0.30"));
        fee.setReason(CancellationFeeReason.SENDER_CANCEL);
        fee.setStatus(CancellationFeeStatus.DUE);
        fee.setGrossFee(BigDecimal.ZERO);
        fee.setRefundFees(BigDecimal.ZERO);
        fee.setCommission(BigDecimal.ZERO);
        fee.setTravelerNet(BigDecimal.ZERO);
        fee.setCurrency("EUR");
        feeRepository.saveAndFlush(fee);
    }

    private long audits(UUID entityId, String action) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Long.class, entityId, action);
        return n == null ? 0 : n;
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("fee-refund-it-" + UUID.randomUUID());
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
