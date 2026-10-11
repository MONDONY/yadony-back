package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
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
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.voucher.CommissionVoucherService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROD_CHECKLIST 9.23 : plus aucune connexion JDBC ni verrou de ligne tenu pendant un appel Stripe
 * de capture ou de versement, sans affaiblir les garanties d'argent.
 *
 * <p>Pool réduit à 2 connexions, délai d'emprunt de 500 ms, Stripe simulé (mocks statiques ouverts
 * sur chaque thread) avec un appel lent de 800 ms. Une connexion tenue pendant l'appel ferait
 * échouer les traitements concurrents au bout de 500 ms. Le « Stripe » simulé applique la règle
 * des clés d'idempotence : même clé = même objet, jamais deux Transfers.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=500"
})
@ActiveProfiles("test")
@DisplayName("Stripe hors transaction : capture et versement sans connexion tenue")
class StripeOutsideTransactionIT {

    private static final long SLOW_CALL_MS = 800;

    @Autowired PaymentRepository paymentRepository;
    @Autowired BidRepository bidRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired StripeReleaseIntentRepository intentRepository;
    @Autowired StripeReleaseGuard releaseGuard;
    @Autowired com.yadony.api.disputes.AdminDisputeOpeningService disputeOpening;
    @Autowired EscrowCaptureService escrowCapture;
    @Autowired RefundProcessor refundProcessor;
    @Autowired AuditService auditService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AdminAlertEscalator alertEscalator;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final StripeTransferLookup lookup = mock(StripeTransferLookup.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final List<UUID> payments = new ArrayList<>();
    private final List<UUID> bids = new ArrayList<>();

    private StripeReleaseExecutor executor;
    private DeliveryEventListener listener;
    private StripeReleaseRecoveryJob job;
    private AdminPaymentController admin;
    private FakeStripe stripe;

    @BeforeEach
    void setUp() throws Exception {
        stripe = new FakeStripe();
        executor = new StripeReleaseExecutor(intentRepository, paymentRepository, lookup, auditService,
                eventPublisher, alertEscalator, transactionManager, releaseGuard);
        listener = new DeliveryEventListener(paymentRepository, userRepository, auditService, bidRepository,
                mock(AdminAlertService.class), mock(CommissionVoucherService.class), null,
                mock(PayoutHoldPolicy.class), alertEscalator, escrowCapture, executor, disputeRepository,
                EscrowCaptureService.requiresNew(transactionManager));
        job = new StripeReleaseRecoveryJob(intentRepository, executor, true, 20);
        admin = new AdminPaymentController(paymentRepository, mock(AdminAlertRepository.class), auditService,
                bidRepository, announcementRepository, userRepository, eventPublisher, mock(ChargebackRepository.class),
                mock(MobileMoneyPayoutInitiator.class), mock(PawapayOperationService.class),
                mock(PawapaySubmissionService.class), mock(RefundProcessor.class), entityManager, transactionManager,
                mock(PayoutHoldPolicy.class), mock(AdminPaymentInsights.class), mock(AdminPaymentTimeline.class),
                escrowCapture, executor, disputeRepository, new ReleaseNetCalculator(null));

        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_traveler");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        AnnouncementEntity announcement = mock(AnnouncementEntity.class);
        when(announcement.getTravelerId()).thenReturn(travelerId);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        // Le « Stripe » simulé est la source de vérité : un Transfer créé (même si sa réponse
        // s'est perdue) est retrouvé par la recherche, comme le ferait StripeTransferLookup.
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
            jdbc.update("DELETE FROM bids WHERE id = ?", id);
        }
    }

    // ── Pool réduit : aucune connexion pendant l'appel Stripe ────────────────

    @Test
    @DisplayName("Capture lente : aucune connexion tenue, 4 captures simultanées sur un pool de 2")
    void capture_slowStripe_holdsNoConnection() throws Exception {
        stripe.slowCapture = true;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(seed(BidStatus.ACCEPTED, false).paymentId);
        }

        // Isolé : pendant l'appel, aucune connexion active (toute connexion serait la sienne).
        stripe.onThisThread(() -> escrowCapture.ensureCaptured(ids.get(0), "test"));
        assertThat(stripe.activeDuringCall).containsOnly(0);

        // Rafale : chacune attend 800 ms chez Stripe, aucune n'échoue faute de connexion.
        List<EscrowCaptureService.Outcome> outcomes = concurrently(ids.subList(1, 4).stream()
                .map(id -> (Callable<EscrowCaptureService.Outcome>) () ->
                        stripe.onThisThread(() -> escrowCapture.ensureCaptured(id, "test")))
                .toList());
        assertThat(outcomes).allMatch(EscrowCaptureService.Outcome::capturedNow);
        for (UUID id : ids) {
            assertThat(paymentRepository.findById(id).orElseThrow().getCapturedAt()).isNotNull();
        }
        assertThat(stripe.captures).hasSize(4);
    }

    @Test
    @DisplayName("Versement lent (livraison) : aucune connexion tenue, 4 versements simultanés sur un pool de 2")
    void delivery_slowTransfer_holdsNoConnection() throws Exception {
        stripe.slowTransfer = true;
        List<Seed> seeds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seeds.add(seed(BidStatus.COMPLETED, true));
        }

        assertThat(stripe.onThisThread(() -> release(seeds.get(0)))).isEqualTo(EscrowReleaseOutcome.RELEASED);
        assertThat(stripe.activeDuringCall).containsOnly(0);

        List<EscrowReleaseOutcome> outcomes = concurrently(seeds.subList(1, 4).stream()
                .map(s -> (Callable<EscrowReleaseOutcome>) () -> stripe.onThisThread(() -> release(s)))
                .toList());
        assertThat(outcomes).containsOnly(EscrowReleaseOutcome.RELEASED);
        for (Seed s : seeds) {
            PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
            assertThat(after.getStripeTransferId()).isEqualTo(stripe.transferOf(s.paymentId));
            assertThat(intentRepository.findByPaymentId(s.paymentId).orElseThrow().getStatus())
                    .isEqualTo(StripeReleaseIntentEntity.Status.DONE);
        }
        assertThat(stripe.transfersCreated()).isEqualTo(4);
        verify(eventPublisher, times(4)).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    @DisplayName("Libération forcée admin : capture et Transfer lents sans connexion tenue")
    void forceRelease_slowStripe_holdsNoConnection() throws Exception {
        stripe.slowTransfer = true;
        Seed s = seed(BidStatus.COMPLETED, true);

        ResponseEntity<?> response = stripe.onThisThread(() -> admin.forceRelease(s.paymentId, null));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(stripe.activeDuringCall).containsOnly(0);
        PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(after.getStripeTransferId()).isEqualTo(stripe.transferOf(s.paymentId));
        assertThat(auditCount(s.paymentId, "ESCROW_FORCE_RELEASED")).isEqualTo(1);
    }

    // ── Concurrence : un seul mouvement d'argent ─────────────────────────────

    @Test
    @DisplayName("Livraison ×2, libération forcée, job et remboursement en même temps : un seul mouvement d'argent")
    void concurrentReleaseForceReleaseJobAndRefund_singleMoneyMovement() throws Exception {
        for (int round = 0; round < 5; round++) {
            stripe = new FakeStripe();
            Seed s = seed(BidStatus.COMPLETED, true);
            List<Callable<Object>> actors = List.of(
                    () -> stripe.onThisThread(() -> release(s)),
                    () -> stripe.onThisThread(() -> release(s)),
                    () -> stripe.onThisThread(() -> {
                        try {
                            return admin.forceRelease(s.paymentId, null);
                        } catch (YadonyBusinessException lost) {
                            return lost.getStatus();
                        }
                    }),
                    () -> stripe.onThisThread(() -> job.run()),
                    () -> stripe.onThisThread(() -> {
                        try {
                            return refundProcessor.processRefund(s.paymentId, "TEST_REFUND", null, Map.of());
                        } catch (IllegalStateException e) {
                            return e.getMessage();
                        }
                    }));
            concurrently(actors);

            int moneyMovements = stripe.transfersCreated() + stripe.refunds.size();
            assertThat(moneyMovements).as("round %d", round).isEqualTo(1);
            PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
            if (stripe.transfersCreated() == 1) {
                assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
                assertThat(after.getStripeTransferId()).isEqualTo(stripe.transferOf(s.paymentId));
                assertThat(auditCount(s.paymentId, "ESCROW_RELEASED_TRANSFER")
                        + auditCount(s.paymentId, "ESCROW_FORCE_RELEASED")).isEqualTo(1);
            } else {
                assertThat(after.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
                assertThat(after.getStripeTransferId()).isNull();
            }
        }
    }

    @Test
    @DisplayName("Reprise concurrente d'un versement en vol (bail échu) : même clé, un seul Transfer, une seule finalisation")
    void jobResumesWhileReleaseInFlight_singleTransferSingleFinalization() throws Exception {
        stripe.slowTransfer = true;
        Seed s = seed(BidStatus.COMPLETED, true);
        CountDownLatch inStripe = new CountDownLatch(1);
        stripe.onTransferCall = inStripe::countDown;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<EscrowReleaseOutcome> delivery = pool.submit(() -> stripe.onThisThread(() -> release(s)));
            assertThat(inStripe.await(10, TimeUnit.SECONDS)).isTrue();
            // Bail échu de force (processus réputé mort) : le job reprend pendant l'appel en vol.
            expireLease(s.paymentId);
            stripe.onTransferCall = () -> { };
            Future<Integer> resumed = pool.submit(() -> stripe.onThisThread(() -> job.run()));
            // Celui qui finalise en second n'est pas compté comme « versé ici » (état explicite).
            assertThat(delivery.get(30, TimeUnit.SECONDS))
                    .isIn(EscrowReleaseOutcome.RELEASED, EscrowReleaseOutcome.RELEASE_IN_PROGRESS);
            resumed.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stripe.transfersCreated()).isEqualTo(1);
        assertThat(auditCount(s.paymentId, "ESCROW_RELEASED_TRANSFER")
                + auditCount(s.paymentId, "TRANSFER_ALREADY_EXISTS_REALIGNED")).isEqualTo(1);
        verify(eventPublisher, org.mockito.Mockito.atMost(1)).publishEvent(any(PaymentReleasedEvent.class));
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStripeTransferId())
                .isEqualTo(stripe.transferOf(s.paymentId));
    }

    // ── Reprise après échec entre l'appel Stripe et la finalisation ──────────

    @Test
    @DisplayName("Réponse Stripe perdue après le Transfer : paiement réservé, le job réaligne sans second Transfer")
    void networkFailureAfterTransfer_jobRealignsWithoutSecondTransfer() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, true);
        stripe.loseNextTransferResponse = true;

        assertThat(stripe.onThisThread(() -> release(s))).isEqualTo(EscrowReleaseOutcome.RELEASE_PENDING);
        PaymentEntity pending = paymentRepository.findById(s.paymentId).orElseThrow();
        // Le claim n'est PAS annulé : un Transfer est peut-être parti, un remboursement est impossible.
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(pending.getStripeTransferId()).isNull();
        StripeReleaseIntentEntity intent = intentRepository.findByPaymentId(s.paymentId).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(StripeReleaseIntentEntity.Status.PENDING);
        assertThat(intent.getLastError()).contains("ApiConnectionException");
        assertThat(refundProcessor.processRefund(s.paymentId, "TEST_REFUND", null, Map.of())).isFalse();

        // Pas encore dû : le job ne fait rien.
        assertThat(stripe.onThisThread(() -> job.run())).isZero();
        expireLease(s.paymentId);
        assertThat(stripe.onThisThread(() -> job.run())).isEqualTo(1);

        assertThat(stripe.transfersCreated()).isEqualTo(1);
        PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(after.getStripeTransferId()).isEqualTo(stripe.transferOf(s.paymentId));
        assertThat(intentRepository.findByPaymentId(s.paymentId).orElseThrow().getStatus())
                .isEqualTo(StripeReleaseIntentEntity.Status.DONE);
        assertThat(auditCount(s.paymentId, "TRANSFER_ALREADY_EXISTS_REALIGNED")).isEqualTo(1);
        // Rejouer encore n'y change rien.
        expireLease(s.paymentId);
        assertThat(stripe.onThisThread(() -> job.run())).isZero();
        assertThat(stripe.transfersCreated()).isEqualTo(1);
    }

    @Test
    @DisplayName("Processus arrêté juste après la réservation : le job verse une seule fois")
    void crashAfterReservation_jobTransfersOnce() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, true);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(paymentRepository.markReleasedIfEscrowAndUnguarded(s.paymentId,
                    java.time.LocalDateTime.now())).isEqualTo(1);
            executor.reserve(new StripeReleaseExecutor.ReleaseSpec(s.paymentId,
                    StripeReleaseIntentEntity.Kind.TRANSFER, 4400L, "eur", "acct_traveler", "ch_x", "delivery",
                    "ESCROW_RELEASED_TRANSFER", s.bidId, travelerId, s.senderId));
        });
        expireLease(s.paymentId);

        assertThat(stripe.onThisThread(() -> job.run())).isEqualTo(1);
        assertThat(stripe.onThisThread(() -> job.run())).isZero();

        assertThat(stripe.transfersCreated()).isEqualTo(1);
        assertThat(stripe.transferAmounts).containsExactly(4400L);
        assertThat(auditCount(s.paymentId, "ESCROW_RELEASED_TRANSFER")).isEqualTo(1);
        verify(eventPublisher, times(1)).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    @DisplayName("Stripe refuse le Transfer : claim annulé, paiement de nouveau en séquestre et remboursable")
    void transferRefused_claimRevertedPaymentBackInEscrow() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, true);
        stripe.refuseTransfers = true;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stripe.onThisThread(() -> release(s)))
                .isInstanceOf(IllegalStateException.class);

        PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(after.getEscrowReleasedAt()).isNull();
        assertThat(intentRepository.findByPaymentId(s.paymentId).orElseThrow().getStatus())
                .isEqualTo(StripeReleaseIntentEntity.Status.REVERTED);
        // Nouvelle tentative possible : la même réservation est réutilisée.
        stripe.refuseTransfers = false;
        assertThat(stripe.onThisThread(() -> release(s))).isEqualTo(EscrowReleaseOutcome.RELEASED);
        assertThat(stripe.transfersCreated()).isEqualTo(1);
    }

    // ── Gardes qui changent entre la réservation et l'appel Stripe ───────────

    @Test
    @DisplayName("Litige bancaire arrivé entre le claim et l'appel : aucun Transfer, séquestre rendu, alerte")
    void chargebackBetweenClaimAndCall_noTransfer() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, true);
        // La recherche Stripe a lieu après le claim commité et avant l'appel : on y fait arriver
        // le webhook charge.dispute.created (seule garde que le paiement réservé ne peut refuser).
        org.mockito.Mockito.doAnswer(inv -> {
            jdbc.update("UPDATE payments SET disputed = true WHERE id = ?", s.paymentId);
            return Optional.empty();
        }).when(lookup).findExistingTransfer(any(), anyString(), any());

        assertThat(stripe.onThisThread(() -> release(s))).isEqualTo(EscrowReleaseOutcome.BLOCKED_BEFORE_TRANSFER);

        assertThat(stripe.transfersCreated()).isZero();
        PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(intentRepository.findByPaymentId(s.paymentId).orElseThrow().getStatus())
                .isEqualTo(StripeReleaseIntentEntity.Status.REVERTED);
        assertThat(auditCount(s.paymentId, "ESCROW_RELEASE_BLOCKED_BEFORE_STRIPE")).isEqualTo(1);
        verify(alertEscalator).raiseOnce(org.mockito.ArgumentMatchers.eq("RELEASE_BLOCKED_" + s.paymentId),
                anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("Annulation du colis entre le claim et l'appel : aucun Transfer")
    void bidCancelledBetweenClaimAndCall_noTransfer() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, true);
        org.mockito.Mockito.doAnswer(inv -> {
            jdbc.update("UPDATE bids SET status = 'CANCELLED' WHERE id = ?", s.bidId);
            return Optional.empty();
        }).when(lookup).findExistingTransfer(any(), anyString(), any());

        assertThat(stripe.onThisThread(() -> release(s))).isEqualTo(EscrowReleaseOutcome.BLOCKED_BEFORE_TRANSFER);
        assertThat(stripe.transfersCreated()).isZero();
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.ESCROW);
    }

    @Test
    @DisplayName("Colis annulé entre le claim et la reprise : recherche vide, séquestre rendu, remboursable")
    void bidCancelledBeforeResume_escrowRestoredAndRefundable() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, true);
        stripe.failNextTransferBeforeCreate = true;   // timeout réseau, rien n'est créé chez Stripe
        assertThat(stripe.onThisThread(() -> release(s))).isEqualTo(EscrowReleaseOutcome.RELEASE_PENDING);
        jdbc.update("UPDATE bids SET status = 'CANCELLED' WHERE id = ?", s.bidId);
        expireLease(s.paymentId);

        assertThat(stripe.onThisThread(() -> job.run())).isEqualTo(1);

        assertThat(stripe.transfersCreated()).isZero();
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(intentRepository.findByPaymentId(s.paymentId).orElseThrow().getStatus())
                .isEqualTo(StripeReleaseIntentEntity.Status.REVERTED);
        // Le paiement est de nouveau remboursable.
        assertThat(stripe.onThisThread(() -> refundProcessor.processRefund(s.paymentId, "TEST_REFUND", null, Map.of())))
                .isTrue();
        assertThat(stripe.refunds).hasSize(1);
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("Compte Connect rejeté après un timeout : recherche vide puis 4xx, séquestre rendu, remboursable")
    void connectRejectedAfterTimeout_escrowRestoredAndRefundable() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, true);
        stripe.failNextTransferBeforeCreate = true;
        assertThat(stripe.onThisThread(() -> release(s))).isEqualTo(EscrowReleaseOutcome.RELEASE_PENDING);
        stripe.refuseTransfers = true;                // account rejected
        expireLease(s.paymentId);

        assertThat(stripe.onThisThread(() -> job.run())).isEqualTo(1);

        assertThat(stripe.transfersCreated()).isZero();
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(stripe.onThisThread(() -> refundProcessor.processRefund(s.paymentId, "TEST_REFUND", null, Map.of())))
                .isTrue();
    }

    @Test
    @DisplayName("Annulation super-admin d'une réservation bloquée : aucun Transfer → ESCROW ; Transfer → réaligné ; recherche KO → rien")
    void adminCancelReservation_threeOutcomes() throws Exception {
        // 1. Aucun Transfer chez Stripe : séquestre rendu, réservation CANCELLED, remboursable.
        Seed none = seed(BidStatus.COMPLETED, true);
        stripe.failNextTransferBeforeCreate = true;
        assertThat(stripe.onThisThread(() -> release(none))).isEqualTo(EscrowReleaseOutcome.RELEASE_PENDING);
        assertThat(executor.cancelReservation(none.paymentId, UUID.randomUUID(), "Compte Connect rejeté").result())
                .isEqualTo(StripeReleaseExecutor.CancelResult.CANCELLED);
        assertThat(paymentRepository.findById(none.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(intentRepository.findByPaymentId(none.paymentId).orElseThrow().getStatus())
                .isEqualTo(StripeReleaseIntentEntity.Status.CANCELLED);
        assertThat(auditCount(none.paymentId, "ESCROW_RELEASE_CANCELLED_BY_ADMIN")).isEqualTo(1);
        // Nouvelle tentative : révision suivante, nouvelle clé.
        assertThat(stripe.onThisThread(() -> release(none))).isEqualTo(EscrowReleaseOutcome.RELEASED);
        assertThat(stripe.transfersByKey).containsKey("transfer-" + none.paymentId + "-r2");

        // 2. Transfer parti malgré la réponse perdue : réaligné, aucun retour en séquestre.
        Seed sent = seed(BidStatus.COMPLETED, true);
        stripe.loseNextTransferResponse = true;
        assertThat(stripe.onThisThread(() -> release(sent))).isEqualTo(EscrowReleaseOutcome.RELEASE_PENDING);
        StripeReleaseExecutor.Cancellation realigned =
                executor.cancelReservation(sent.paymentId, UUID.randomUUID(), "Vérification admin");
        assertThat(realigned.result()).isEqualTo(StripeReleaseExecutor.CancelResult.REALIGNED);
        assertThat(realigned.transferId()).isEqualTo(stripe.transferOf(sent.paymentId));
        assertThat(paymentRepository.findById(sent.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.RELEASED);

        // 3. Recherche Stripe impossible : rien ne change.
        Seed down = seed(BidStatus.COMPLETED, true);
        stripe.failNextTransferBeforeCreate = true;
        assertThat(stripe.onThisThread(() -> release(down))).isEqualTo(EscrowReleaseOutcome.RELEASE_PENDING);
        org.mockito.Mockito.doThrow(new ApiConnectionException("timeout"))
                .when(lookup).findExistingTransfer(org.mockito.ArgumentMatchers.eq(down.paymentId), anyString(), any());
        assertThat(executor.cancelReservation(down.paymentId, UUID.randomUUID(), "Vérification admin").result())
                .isEqualTo(StripeReleaseExecutor.CancelResult.LOOKUP_FAILED);
        assertThat(paymentRepository.findById(down.paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(intentRepository.findByPaymentId(down.paymentId).orElseThrow().getStatus())
                .isEqualTo(StripeReleaseIntentEntity.Status.PENDING);
    }

    @Test
    @DisplayName("Annulation super-admin pendant un appel en vol : refusée")
    void adminCancelWhileInFlight_refused() throws Exception {
        stripe.slowTransfer = true;
        Seed s = seed(BidStatus.COMPLETED, true);
        CountDownLatch inStripe = new CountDownLatch(1);
        stripe.onTransferCall = inStripe::countDown;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<EscrowReleaseOutcome> delivery = pool.submit(() -> stripe.onThisThread(() -> release(s)));
            assertThat(inStripe.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.cancelReservation(s.paymentId, UUID.randomUUID(), "Vérification admin").result())
                    .isEqualTo(StripeReleaseExecutor.CancelResult.IN_PROGRESS);
            assertThat(delivery.get(30, TimeUnit.SECONDS)).isEqualTo(EscrowReleaseOutcome.RELEASED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stripe.transfersCreated()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deux livraisons simultanées d'un séquestre non capturé : une capture, aucune fausse alerte, un versement")
    void concurrentCaptures_noFalseAlert_releaseNotAbandoned() throws Exception {
        stripe.slowCapture = true;
        Seed s = seed(BidStatus.COMPLETED, false);
        List<EscrowReleaseOutcome> outcomes = concurrently(List.of(
                () -> stripe.onThisThread(() -> release(s)),
                () -> stripe.onThisThread(() -> release(s))));

        assertThat(stripe.captures).hasSize(1);
        assertThat(stripe.transfersCreated()).isEqualTo(1);
        assertThat(outcomes).contains(EscrowReleaseOutcome.RELEASED)
                .doesNotContain(EscrowReleaseOutcome.CAPTURE_FAILED);
        org.mockito.Mockito.verify(alertEscalator, org.mockito.Mockito.never()).raiseOnce(
                org.mockito.ArgumentMatchers.eq("ESCROW_CAPTURE_FAILED_" + s.paymentId), anyString(),
                org.mockito.ArgumentMatchers.anyMap());
        assertThat(paymentRepository.findById(s.paymentId).orElseThrow().getCapturedAt()).isNotNull();
    }

    @Test
    @DisplayName("Ouverture d'un litige admin et remboursement pendant le versement en cours : refusés proprement")
    void disputeAndRefundDuringReservedRelease_areRefused() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, true);
        List<Object> concurrent = new CopyOnWriteArrayList<>();
        stripe.onTransferCall = () -> {
            try {
                disputeOpening.open(s.bidId, UUID.randomUUID(), new com.yadony.api.disputes.dto.AdminOpenDisputeRequest(
                        com.yadony.api.disputes.DisputeParty.SENDER,
                        com.yadony.api.disputes.AdminDisputeReason.PARCEL_DAMAGED, "Colis abîmé à l'arrivée"));
                concurrent.add("dispute-opened");
            } catch (YadonyBusinessException e) {
                concurrent.add(e.getErrorCode());
            }
            concurrent.add(refundProcessor.processRefund(s.paymentId, "TEST_REFUND", null, Map.of()));
        };

        assertThat(stripe.onThisThread(() -> release(s))).isEqualTo(EscrowReleaseOutcome.RELEASED);

        assertThat(concurrent).containsExactly("payment-already-released", false);
        assertThat(stripe.transfersCreated()).isEqualTo(1);
        assertThat(stripe.refunds).isEmpty();
    }

    @Test
    @DisplayName("Libération forcée : litige bancaire arrivé pendant la capture, refusé sous verrou avant le claim")
    void forceRelease_chargebackDuringCapture_refusedUnderLock() throws Exception {
        Seed s = seed(BidStatus.COMPLETED, false);
        stripe.onCapture = () -> jdbc.update("UPDATE payments SET disputed = true WHERE id = ?", s.paymentId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        stripe.onThisThread(() -> admin.forceRelease(s.paymentId, null)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("payout-guard-changed"));

        assertThat(stripe.transfersCreated()).isZero();
        PaymentEntity after = paymentRepository.findById(s.paymentId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(intentRepository.findByPaymentId(s.paymentId)).isEmpty();
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    private record Seed(UUID bidId, UUID paymentId, UUID senderId) {}

    private Seed seed(BidStatus bidStatus, boolean captured) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(bidStatus);
        BidEntity bid = bidRepository.saveAndFlush(b);
        bids.add(bid.getId());
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setStripeChargeId(captured ? "ch_x" : null);
        p.setAmount(new BigDecimal("50.00"));
        p.setCommissionAmount(new BigDecimal("6.00"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        if (captured) {
            p.setCapturedAt(Instant.now());
        }
        PaymentEntity saved = paymentRepository.saveAndFlush(p);
        payments.add(saved.getId());
        if (captured) {
            stripe.capturedPis.add(saved.getStripePaymentIntentId());
        }
        return new Seed(bid.getId(), saved.getId(), bid.getSenderId());
    }

    private EscrowReleaseOutcome release(Seed s) {
        return listener.releaseAfterLateEscrow(s.bidId, s.senderId, travelerId, "delivery");
    }

    private void expireLease(UUID paymentId) {
        // Par JPA, comme le job (même conversion de fuseau que hibernate.jdbc.time_zone).
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> entityManager
                .createQuery("UPDATE StripeReleaseIntentEntity i SET i.leaseUntil = :t WHERE i.paymentId = :p")
                .setParameter("t", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1))
                .setParameter("p", paymentId)
                .executeUpdate());
    }

    private int auditCount(UUID paymentId, String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, paymentId, action);
        return n == null ? 0 : n;
    }

    private int activeConnections() {
        return ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
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
     * Stripe simulé, partagé entre threads : une clé d'idempotence rend toujours le même objet,
     * comme chez Stripe. Les mocks statiques du SDK sont ouverts sur chaque thread appelant.
     */
    private final class FakeStripe {
        final Map<String, String> transfersByKey = new ConcurrentHashMap<>();
        final Map<UUID, String> transfersByPayment = new ConcurrentHashMap<>();
        final List<Long> transferAmounts = new CopyOnWriteArrayList<>();
        final List<String> captures = new CopyOnWriteArrayList<>();
        final java.util.Set<String> capturedPis = ConcurrentHashMap.newKeySet();
        final List<String> refunds = new CopyOnWriteArrayList<>();
        final List<Integer> activeDuringCall = new CopyOnWriteArrayList<>();
        final AtomicInteger counter = new AtomicInteger();
        volatile boolean slowCapture;
        volatile boolean slowTransfer;
        volatile boolean loseNextTransferResponse;
        volatile boolean refuseTransfers;
        volatile boolean failNextTransferBeforeCreate;
        final java.util.Set<String> capturing = ConcurrentHashMap.newKeySet();
        volatile Runnable onTransferCall = () -> { };
        volatile Runnable onCapture = () -> { };

        String transferOf(Object paymentId) {
            return transfersByPayment.get((UUID) paymentId);
        }

        int transfersCreated() {
            return transfersByKey.size();
        }

        private void slow() throws InterruptedException {
            Thread.sleep(SLOW_CALL_MS);
            activeDuringCall.add(activeConnections());
        }

        <T> T onThisThread(Callable<T> body) throws Exception {
            try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class);
                 MockedStatic<Transfer> tr = mockStatic(Transfer.class);
                 MockedStatic<Refund> rf = mockStatic(Refund.class)) {
                pi.when(() -> PaymentIntent.retrieve(anyString(), any(PaymentIntentRetrieveParams.class), isNull()))
                        .thenAnswer(inv -> intent(inv.getArgument(0)));
                pi.when(() -> PaymentIntent.retrieve(anyString())).thenAnswer(inv -> intent(inv.getArgument(0)));
                tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                        .thenAnswer(inv -> {
                            onTransferCall.run();
                            if (failNextTransferBeforeCreate) {
                                failNextTransferBeforeCreate = false;
                                throw new ApiConnectionException("connect timed out");   // rien de créé
                            }
                            if (slowTransfer) {
                                slow();
                            }
                            if (refuseTransfers) {
                                throw new com.stripe.exception.InvalidRequestException(
                                        "insufficient funds", null, "req", null, 400, null);
                            }
                            TransferCreateParams params = inv.getArgument(0);
                            String key = ((RequestOptions) inv.getArgument(1)).getIdempotencyKey();
                            String id = transfersByKey.computeIfAbsent(key, k -> {
                                transferAmounts.add(params.getAmount());
                                return "tr_" + counter.incrementAndGet();
                            });
                            transfersByPayment.put(UUID.fromString(params.getMetadata().get("payment_id")), id);
                            if (loseNextTransferResponse) {
                                loseNextTransferResponse = false;
                                throw new ApiConnectionException("Read timed out");
                            }
                            Transfer t = mock(Transfer.class);
                            when(t.getId()).thenReturn(id);
                            return t;
                        });
                rf.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                        .thenAnswer(inv -> {
                            refunds.add(((RequestOptions) inv.getArgument(1)).getIdempotencyKey());
                            return mock(Refund.class);
                        });
                return body.call();
            }
        }

        private PaymentIntent intent(String piId) throws Exception {
            // Aucun accès base ici : l'état « capturé » est celui du Stripe simulé.
            boolean captured = capturedPis.contains(piId) || captures.contains(piId);
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getId()).thenReturn(piId);
            when(pi.getStatus()).thenReturn(captured ? "succeeded" : "requires_capture");
            when(pi.getAmount()).thenReturn(5000L);
            when(pi.getAmountCapturable()).thenReturn(captured ? 0L : 5000L);
            when(pi.getCurrency()).thenReturn("eur");
            when(pi.getLatestCharge()).thenReturn("ch_x");
            when(pi.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
                // Même clé en cours de traitement chez Stripe : erreur d'idempotence (409).
                if (!capturing.add(piId)) {
                    throw new com.stripe.exception.IdempotencyException("request in progress", "req", null, 409);
                }
                try {
                    if (slowCapture) {
                        slow();
                    }
                    onCapture.run();
                    if (!captures.contains(piId)) {
                        captures.add(piId);
                    }
                } finally {
                    capturing.remove(piId);
                }
                return pi;
            });
            return pi;
        }
    }
}
