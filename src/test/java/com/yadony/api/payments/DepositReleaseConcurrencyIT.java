package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.admin.AdminPaymentController;
import com.yadony.api.admin.AdminPaymentInsights;
import com.yadony.api.admin.AdminPaymentTimeline;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.tracking.TrackingEventEntity;
import com.yadony.api.tracking.TrackingEventRepository;
import com.yadony.api.tracking.TrackingEventType;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.tracking.events.ParcelDepositedEvent;
import com.yadony.api.voucher.CommissionVoucherService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Versement au dépôt (BB5) : idempotence et concurrence, base et transactions réelles, Stripe simulé par thread (même
 * gabarit que {@code StripeOutsideTransactionIT} et même configuration de contexte que
 * {@code DeliveryTransferConcurrencyIT}). Invariant : un seul mouvement d'argent par paiement, quels que soient les
 * déclencheurs simultanés (dépôt rejoué, séquestre tardif, libération forcée admin, livraison) ; un échec réseau est
 * rejoué avec la même clé d'idempotence.
 *
 * <p>Vérification de détection (notée dans la PR) : avec le claim atomique {@code markReleasedIfEscrowAndUnguarded}
 * neutralisé (UPDATE sans condition de statut), {@link #sameDepositEventTwiceInParallel_oneTransfer} échoue.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Versement au dépôt : un seul mouvement d'argent sous concurrence et rejeu")
class DepositReleaseConcurrencyIT {

    private static final int ROUNDS = 5;

    @Autowired PaymentRepository paymentRepository;
    @Autowired BidRepository bidRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired EscrowCaptureService escrowCapture;
    @Autowired AuditService auditService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired StripeReleaseIntentRepository intentRepository;
    @Autowired StripeReleaseGuard releaseGuard;
    @Autowired TrackingEventRepository trackingEventRepository;
    @MockitoBean AdminAlertEscalator alertEscalator;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final StripeTransferLookup lookup = mock(StripeTransferLookup.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final MobileMoneyPayoutInitiator payoutInitiator = mock(MobileMoneyPayoutInitiator.class);
    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final List<UUID> payments = new ArrayList<>();
    private final List<UUID> bids = new ArrayList<>();

    private DeliveryEventListener listener;
    private DeliveredEscrowReleaser releaser;
    private StripeReleaseRecoveryJob job;
    private AdminPaymentController admin;
    private FakeStripe stripe;

    @BeforeEach
    void setUp() throws Exception {
        stripe = new FakeStripe();
        StripeReleaseExecutor executor = new StripeReleaseExecutor(intentRepository, paymentRepository, lookup,
                auditService, eventPublisher, alertEscalator, transactionManager, releaseGuard);
        // Sans transaction autour du versement, comme en production (PROD_CHECKLIST 9.23).
        listener = new DeliveryEventListener(paymentRepository, userRepository, auditService, bidRepository,
                mock(AdminAlertService.class), mock(CommissionVoucherService.class), payoutInitiator,
                mock(PayoutHoldPolicy.class), alertEscalator, escrowCapture, executor, disputeRepository,
                transactionManager);
        releaser = new DeliveredEscrowReleaser(paymentRepository, bidRepository, announcementRepository, listener,
                alertEscalator);
        job = new StripeReleaseRecoveryJob(intentRepository, executor, true, 20);
        admin = new AdminPaymentController(paymentRepository, mock(AdminAlertRepository.class), auditService,
                bidRepository, announcementRepository, userRepository, eventPublisher, mock(ChargebackRepository.class),
                mock(MobileMoneyPayoutInitiator.class), mock(PawapayOperationService.class),
                mock(PawapaySubmissionService.class), mock(RefundProcessor.class), entityManager, transactionManager,
                mock(PayoutHoldPolicy.class), mock(AdminPaymentInsights.class), mock(AdminPaymentTimeline.class),
                escrowCapture, executor, disputeRepository, new ReleaseNetCalculator(null), java.time.Clock.systemUTC(),
                com.yadony.api.admin.AdminPaymentLinksTestSupport.none());

        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_carrier");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        AnnouncementEntity announcement = mock(AnnouncementEntity.class);
        when(announcement.getTravelerId()).thenReturn(travelerId);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        // Le « Stripe » simulé est la source de vérité : un Transfer créé est retrouvé par la recherche.
        when(lookup.findExistingTransfer(any(), anyString(), any()))
                .thenAnswer(inv -> Optional.ofNullable(stripe.transferOf(inv.getArgument(0))));
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : payments) {
            jdbc.update("DELETE FROM stripe_release_intents WHERE payment_id = ?", id);
            jdbc.update("DELETE FROM payments WHERE id = ?", id);
        }
        for (UUID id : bids) {
            jdbc.update("DELETE FROM tracking_events WHERE bid_id = ?", id);
            jdbc.update("DELETE FROM bids WHERE id = ?", id);
        }
    }

    // ── Concurrence ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("même dépôt traité deux fois en parallèle : un Transfer, un audit, un PaymentReleasedEvent « au dépôt »")
    void sameDepositEventTwiceInParallel_oneTransfer() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            stripe = new FakeStripe();
            clearInvocations(eventPublisher);
            Seed s = seed(PaymentRail.STRIPE);

            List<EscrowReleaseOutcome> outcomes = concurrently(List.of(
                    () -> stripe.onThisThread(() -> listener.releaseAtDeposit(s.event())),
                    () -> stripe.onThisThread(() -> listener.releaseAtDeposit(s.event()))));

            assertThat(outcomes).as("round %d", round).containsOnlyOnce(EscrowReleaseOutcome.RELEASED);
            assertThat(stripe.transfersCreated()).as("round %d", round).isEqualTo(1);
            // Le perdant n'appelle jamais Stripe (claim perdu ou paiement déjà sorti du séquestre).
            assertThat(stripe.keys).as("round %d", round).containsExactly("transfer-" + s.paymentId + "-r1");
            PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
            assertThat(after.getStripeTransferId()).isEqualTo(stripe.transferOf(s.paymentId));
            assertThat(auditCount(s.paymentId, "ESCROW_RELEASED_DEPOSIT")).as("round %d", round).isEqualTo(1);
            // Un seul PaymentReleasedEvent : c'est lui qui déclenche ensuite le versement des modifications et
            // suppléments acquis avant le dépôt (BidAmendmentPayoutListener, BidSupplement*Listener).
            verify(eventPublisher, times(1)).publishEvent(org.mockito.ArgumentMatchers.<Object>argThat(e ->
                    e instanceof PaymentReleasedEvent released && released.isDeposit()));
        }
    }

    @Test
    @DisplayName("dépôt ×2, séquestre tardif et libération forcée admin en même temps : un seul Transfer")
    void depositLateEscrowAndAdminForceReleaseInParallel_oneTransfer() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            stripe = new FakeStripe();
            Seed s = seed(PaymentRail.STRIPE);

            concurrently(List.<Callable<Object>>of(
                    () -> stripe.onThisThread(() -> listener.releaseAtDeposit(s.event())),
                    () -> stripe.onThisThread(() -> listener.releaseAtDeposit(s.event())),
                    () -> stripe.onThisThread(() -> releaser.releaseIfDelivered(s.paymentId,
                            DeliveryEventListener.SOURCE_LATE_ESCROW).outcome()),
                    () -> stripe.onThisThread(() -> {
                        try {
                            return admin.forceRelease(s.paymentId, null);
                        } catch (YadonyBusinessException lost) {
                            return lost.getStatus();
                        }
                    })));

            assertThat(stripe.transfersCreated()).as("round %d", round).isEqualTo(1);
            PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
            assertThat(after.getStripeTransferId()).isEqualTo(stripe.transferOf(s.paymentId));
            // Une seule finalisation (réalignement compris : instabilité connue de StripeOutsideTransactionIT:281).
            assertThat(auditCount(s.paymentId, "ESCROW_RELEASED_DEPOSIT")
                    + auditCount(s.paymentId, "ESCROW_RELEASED_TRANSFER")
                    + auditCount(s.paymentId, "ESCROW_FORCE_RELEASED")
                    + auditCount(s.paymentId, "TRANSFER_ALREADY_EXISTS_REALIGNED")).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("mobile money : même dépôt traité deux fois en parallèle, un seul payout pawaPay")
    void depositMobileMoneyTwiceInParallel_onePawapayPayout() throws Exception {
        AtomicInteger payouts = new AtomicInteger();
        when(payoutInitiator.release(any(), any(), any(), any(), anyString())).thenAnswer(inv -> {
            payouts.incrementAndGet();
            return null;
        });
        for (int round = 0; round < ROUNDS; round++) {
            payouts.set(0);
            Seed s = seed(PaymentRail.PAWAPAY);

            List<EscrowReleaseOutcome> outcomes = concurrently(List.of(
                    () -> listener.releaseAtDeposit(s.event()),
                    () -> listener.releaseAtDeposit(s.event())));

            assertThat(outcomes).as("round %d", round).containsOnlyOnce(EscrowReleaseOutcome.RELEASED);
            assertThat(payouts.get()).as("round %d", round).isEqualTo(1);
            assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.RELEASED);
        }
        verify(payoutInitiator, times(ROUNDS)).release(any(), any(), eq(travelerId), any(),
                eq(DeliveryEventListener.SOURCE_DEPOSIT));
    }

    // ── Rejeu ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("échec réseau avant création puis reprise : même clé transfer-<id>-r1, un seul Transfer")
    void stripeTimeoutThenRetry_sameIdempotencyKey() throws Exception {
        Seed s = seed(PaymentRail.STRIPE);
        stripe.failNextTransferBeforeCreate = true;

        assertThat(stripe.onThisThread(() -> listener.releaseAtDeposit(s.event())))
                .isEqualTo(EscrowReleaseOutcome.RELEASE_PENDING);
        // Issue incertaine : le claim tient (un Transfer a pu partir), le paiement reste réservé.
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.RELEASED);
        // Le dépôt rejoué (file hors ligne, événement redélivré) ne relance rien.
        assertThat(stripe.onThisThread(() -> listener.releaseAtDeposit(s.event())))
                .isEqualTo(EscrowReleaseOutcome.NOT_IN_ESCROW);

        expireLease(s.paymentId);
        assertThat(stripe.onThisThread(() -> job.run())).isEqualTo(1);

        assertThat(stripe.transfersCreated()).isEqualTo(1);
        assertThat(stripe.keys).hasSize(2).containsOnly("transfer-" + s.paymentId + "-r1");
        PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(after.getStripeTransferId()).isEqualTo(stripe.transferOf(s.paymentId));
        assertThat(auditCount(s.paymentId, "ESCROW_RELEASED_DEPOSIT")).isEqualTo(1);
    }

    @Test
    @DisplayName("dépôt versé puis livraison et auto-réparation : aucun second versement")
    void depositThenDelivery_noSecondTransfer() throws Exception {
        Seed s = seed(PaymentRail.STRIPE);

        assertThat(stripe.onThisThread(() -> listener.releaseAtDeposit(s.event())))
                .isEqualTo(EscrowReleaseOutcome.RELEASED);
        jdbc.update("UPDATE bids SET status = 'COMPLETED' WHERE id = ?", s.bidId);

        stripe.onThisThread(() -> {
            listener.handleDeliveryConfirmed(new DeliveryConfirmedEvent(s.bidId, s.senderId, travelerId));
            return null;
        });
        assertThat(stripe.onThisThread(() -> releaser.releaseIfDelivered(s.paymentId, "auto-heal-release").outcome()))
                .isEqualTo(EscrowReleaseOutcome.NOT_DELIVERED);
        assertThat(stripe.onThisThread(() -> listener.releaseAtDeposit(s.event())))
                .isEqualTo(EscrowReleaseOutcome.NOT_IN_ESCROW);

        assertThat(stripe.transfersCreated()).isEqualTo(1);
        assertThat(stripe.keys).hasSize(1);
        assertThat(auditCount(s.bidId, "DELIVERY_RELEASE_SKIPPED_DEPOSIT")).isEqualTo(1);
        assertThat(auditCount(s.paymentId, "ESCROW_RELEASED_DEPOSIT")).isEqualTo(1);
    }

    @Test
    @DisplayName("dépôt bloqué (compte Connect pas prêt) puis séquestre rattrapé : versé une fois, par le rattrapage")
    void depositBlocked_thenLateRelease_paidOnce() throws Exception {
        Seed s = seed(PaymentRail.STRIPE);
        UserEntity notReady = new UserEntity();
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(notReady));

        assertThat(stripe.onThisThread(() -> listener.releaseAtDeposit(s.event())))
                .isEqualTo(EscrowReleaseOutcome.TRANSFER_FAILED);
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(stripe.transfersCreated()).isZero();
        verify(alertEscalator).raiseOnce(eq(DeliveryEventListener.DEPOSIT_FAILED_ALERT_PREFIX + s.bidId), anyString(),
                org.mockito.ArgumentMatchers.anyMap());

        UserEntity ready = new UserEntity();
        ready.setStripeAccountId("acct_carrier");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(ready));
        assertThat(stripe.onThisThread(() -> releaser.releaseIfDelivered(s.paymentId,
                DeliveryEventListener.SOURCE_LATE_ESCROW).outcome())).isEqualTo(EscrowReleaseOutcome.RELEASED);

        assertThat(stripe.transfersCreated()).isEqualTo(1);
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.RELEASED);
    }

    // ── Rattrapage planifié (événement perdu) ───────────────────────────────

    @Test
    @DisplayName("événement du dépôt perdu : le rattrapage planifié verse une fois, même en course avec un dépôt tardif")
    void depositEventLost_retryJobPaysOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            stripe = new FakeStripe();
            Seed s = seed(PaymentRail.STRIPE);
            DepositReleaseRetryJob retry = retryJob();

            concurrently(List.<Callable<Object>>of(
                    () -> stripe.onThisThread(retry::run),
                    () -> stripe.onThisThread(() -> listener.releaseAtDeposit(s.event()))));
            // Un nouveau passage ne trouve plus rien à verser.
            stripe.onThisThread(retryJob()::run);

            assertThat(stripe.transfersCreated()).as("round %d", round).isEqualTo(1);
            assertThat(stripe.keys).as("round %d", round).containsOnly("transfer-" + s.paymentId + "-r1");
            assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus())
                    .isEqualTo(PaymentStatus.RELEASED);
            assertThat(auditCount(s.paymentId, "ESCROW_RELEASED_DEPOSIT")).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("mobile money : dépôt bloqué puis rattrapé par la passe planifiée, un seul payout")
    void depositMobileMoneyRetried_onePawapayPayout() throws Exception {
        AtomicInteger payouts = new AtomicInteger();
        when(payoutInitiator.release(any(), any(), any(), any(), anyString())).thenAnswer(inv -> {
            payouts.incrementAndGet();
            return null;
        });
        Seed s = seed(PaymentRail.PAWAPAY);
        DepositReleaseRetryJob retry = retryJob();

        concurrently(List.<Callable<Object>>of(retry::run, () -> listener.releaseAtDeposit(s.event())));
        retryJob().run();

        assertThat(payouts.get()).isEqualTo(1);
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.RELEASED);
    }

    private DepositReleaseRetryJob retryJob() {
        return new DepositReleaseRetryJob(paymentRepository, bidRepository, announcementRepository, listener,
                alertEscalator, true, java.time.Duration.ofMinutes(1), java.time.Duration.ofDays(3),
                java.time.Duration.ofHours(6), 50, java.time.Clock.systemUTC());
    }

    // ── Gabarits ─────────────────────────────────────────────────────────────

    private record Seed(UUID bidId, UUID paymentId, UUID senderId, UUID travelerId) {
        ParcelDepositedEvent event() {
            return new ParcelDepositedEvent(bidId, senderId, travelerId);
        }
    }

    /** Colis payé au dépôt, déposé (DEPART), paiement en séquestre capturé. */
    private Seed seed(PaymentRail rail) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(BidStatus.HANDED_OVER);
        ReflectionTestUtils.setField(b, "payoutTrigger", PayoutTrigger.DEPOSIT);
        ReflectionTestUtils.setField(b, "payoutTriggerFrozenAt", LocalDateTime.now(ZoneOffset.UTC).minusDays(3));
        BidEntity bid = bidRepository.saveAndFlush(b);
        bids.add(bid.getId());
        TrackingEventEntity depart = new TrackingEventEntity();
        depart.setBidId(bid.getId());
        depart.setEventType(TrackingEventType.DEPART);
        depart.setScannedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        depart.setPhotoUrl("tracking/" + bid.getId() + "/deposit.jpg");
        trackingEventRepository.saveAndFlush(depart);

        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setRail(rail);
        p.setStripePaymentIntentId(rail == PaymentRail.STRIPE ? "pi_" + UUID.randomUUID() : null);
        p.setStripeChargeId(rail == PaymentRail.STRIPE ? "ch_x" : null);
        p.setAmount(rail == PaymentRail.STRIPE ? new BigDecimal("50.00") : new BigDecimal("30000"));
        p.setCommissionAmount(rail == PaymentRail.STRIPE ? new BigDecimal("6.00") : new BigDecimal("3600"));
        p.setCurrency(rail == PaymentRail.STRIPE ? "EUR" : "XOF");
        p.setStatus(PaymentStatus.ESCROW);
        if (rail == PaymentRail.STRIPE) {
            p.setCapturedAt(Instant.now());
        }
        PaymentEntity saved = paymentRepository.saveAndFlush(p);
        payments.add(saved.getId());
        return new Seed(bid.getId(), saved.getId(), bid.getSenderId(), travelerId);
    }

    private void expireLease(UUID paymentId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> entityManager
                .createQuery("UPDATE StripeReleaseIntentEntity i SET i.leaseUntil = :t WHERE i.paymentId = :p")
                .setParameter("t", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .setParameter("p", paymentId)
                .executeUpdate());
    }

    private int auditCount(UUID entityId, String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, entityId, action);
        return n == null ? 0 : n;
    }

    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Stripe simulé, partagé entre threads : une clé d'idempotence rend toujours le même Transfer, comme chez Stripe.
     * Les mocks statiques du SDK sont ouverts sur chaque thread appelant.
     */
    private static final class FakeStripe {
        final Map<String, String> transfersByKey = new ConcurrentHashMap<>();
        final Map<UUID, String> transfersByPayment = new ConcurrentHashMap<>();
        /** Clé de chaque appel à Transfer.create, quelle que soit son issue. */
        final List<String> keys = new CopyOnWriteArrayList<>();
        final AtomicInteger counter = new AtomicInteger();
        volatile boolean failNextTransferBeforeCreate;

        String transferOf(Object paymentId) {
            return transfersByPayment.get((UUID) paymentId);
        }

        int transfersCreated() {
            return transfersByKey.size();
        }

        <T> T onThisThread(Callable<T> body) throws Exception {
            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class);
                 MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
                pi.when(() -> PaymentIntent.retrieve(anyString(), any(PaymentIntentRetrieveParams.class), isNull()))
                        .thenAnswer(inv -> captured(inv.getArgument(0)));
                pi.when(() -> PaymentIntent.retrieve(anyString())).thenAnswer(inv -> captured(inv.getArgument(0)));
                tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                        .thenAnswer(inv -> {
                            TransferCreateParams params = inv.getArgument(0);
                            String key = ((RequestOptions) inv.getArgument(1)).getIdempotencyKey();
                            keys.add(key);
                            if (failNextTransferBeforeCreate) {
                                failNextTransferBeforeCreate = false;
                                throw new ApiConnectionException("connect timed out");   // rien de créé
                            }
                            String id = transfersByKey.computeIfAbsent(key, k -> "tr_" + counter.incrementAndGet());
                            transfersByPayment.put(UUID.fromString(params.getMetadata().get("payment_id")), id);
                            Transfer t = mock(Transfer.class);
                            when(t.getId()).thenReturn(id);
                            return t;
                        });
                return body.call();
            }
        }

        private static PaymentIntent captured(String piId) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getId()).thenReturn(piId);
            when(pi.getStatus()).thenReturn("succeeded");
            when(pi.getAmount()).thenReturn(5000L);
            when(pi.getAmountCapturable()).thenReturn(0L);
            when(pi.getCurrency()).thenReturn("eur");
            when(pi.getLatestCharge()).thenReturn("ch_x");
            return pi;
        }
    }
}
