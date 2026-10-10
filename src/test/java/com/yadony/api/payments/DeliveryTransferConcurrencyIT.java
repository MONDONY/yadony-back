package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Anti double Transfer sous concurrence (base réelle, Stripe simulé par thread) : la recherche
 * Stripe d'un Transfer existant n'a lieu qu'APRÈS le claim atomique ESCROW → RELEASED, qu'un
 * seul traitement gagne ; et une lecture Stripe en échec n'émet jamais de Transfer (fail-closed).
 */
@SpringBootTest
@ActiveProfiles("test")
class DeliveryTransferConcurrencyIT {

    @Autowired PaymentRepository paymentRepository;
    @Autowired BidRepository bidRepository;
    @Autowired DisputeRepository disputeRepository;
    @Autowired EscrowCaptureService escrowCapture;
    @Autowired AuditService auditService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AdminAlertEscalator alertEscalator;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final StripeTransferLookup lookup = mock(StripeTransferLookup.class);
    private final Queue<String> transfers = new ConcurrentLinkedQueue<>();
    private final UUID travelerId = UUID.randomUUID();
    private TransactionTemplate tx;
    private DeliveryEventListener listener;
    private BidEntity bid;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        listener = new DeliveryEventListener(paymentRepository, userRepository, auditService,
                mock(ApplicationEventPublisher.class), bidRepository, mock(AdminAlertService.class),
                mock(CommissionVoucherService.class), null, mock(PayoutHoldPolicy.class), alertEscalator,
                escrowCapture, lookup, disputeRepository) {
            @Override
            public EscrowReleaseOutcome releaseAfterLateEscrow(UUID bidId, UUID senderId, UUID travelerId, String source) {
                return tx.execute(s -> super.releaseAfterLateEscrow(bidId, senderId, travelerId, source));
            }
        };
        UUID announcementId = UUID.randomUUID();
        AnnouncementEntity announcement = mock(AnnouncementEntity.class);
        when(announcement.getTravelerId()).thenReturn(travelerId);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));
        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_traveler");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(BidStatus.COMPLETED);
        bid = bidRepository.saveAndFlush(b);
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setStripeChargeId("ch_concurrency");
        p.setAmount(new BigDecimal("50.00"));
        p.setCommissionAmount(new BigDecimal("6.00"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        payment = paymentRepository.saveAndFlush(p);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payments WHERE id = ?", payment.getId());
        jdbc.update("DELETE FROM bids WHERE id = ?", bid.getId());
    }

    /** Un versement complet sur ce thread, Stripe simulé localement (les mocks statiques sont par thread). */
    private EscrowReleaseOutcome releaseOnThisThread() {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        when(pi.getLatestCharge()).thenReturn("ch_concurrency");
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            piStatic.when(() -> PaymentIntent.retrieve(anyString(), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenReturn(pi);
            trStatic.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenAnswer(inv -> {
                        transfers.add(((RequestOptions) inv.getArgument(1)).getIdempotencyKey());
                        Transfer t = mock(Transfer.class);
                        when(t.getId()).thenReturn("tr_" + transfers.size());
                        return t;
                    });
            return listener.releaseAfterLateEscrow(bid.getId(), bid.getSenderId(), travelerId, "concurrency");
        }
    }

    @Test
    void deuxVersementsSimultanes_unSeulTransfer() throws Exception {
        // Le gagnant tient son verrou un moment pendant la recherche Stripe : le perdant attend.
        when(lookup.findExistingTransfer(any(), anyString(), any())).thenAnswer(inv -> {
            Thread.sleep(300);
            return Optional.empty();
        });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<EscrowReleaseOutcome>> results = List.of(
                    pool.submit(() -> { start.await(); return releaseOnThisThread(); }),
                    pool.submit(() -> { start.await(); return releaseOnThisThread(); }));
            start.countDown();
            List<EscrowReleaseOutcome> outcomes = List.of(results.get(0).get(30, TimeUnit.SECONDS),
                    results.get(1).get(30, TimeUnit.SECONDS));

            assertThat(outcomes).containsExactlyInAnyOrder(EscrowReleaseOutcome.RELEASED,
                    EscrowReleaseOutcome.ALREADY_RELEASED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(transfers).containsExactly("transfer-" + payment.getId());
        verify(lookup, times(1)).findExistingTransfer(any(), anyString(), any());
        PaymentEntity after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(after.getStripeTransferId()).isEqualTo("tr_1");
    }

    @Test
    void transferDejaEmis_aucunSecondTransfer_idTrace() throws Exception {
        when(lookup.findExistingTransfer(eq(payment.getId()), eq("acct_traveler"), any()))
                .thenReturn(Optional.of("tr_existing"));

        assertThat(releaseOnThisThread()).isEqualTo(EscrowReleaseOutcome.RELEASED);

        assertThat(transfers).isEmpty();
        PaymentEntity after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(after.getStripeTransferId()).isEqualTo("tr_existing");
    }

    @Test
    void lectureStripeEnEchec_aucunTransfer_paiementResteEscrow_alerte() throws Exception {
        when(lookup.findExistingTransfer(any(), anyString(), any())).thenThrow(new ApiConnectionException("timeout"));
        DeliveredEscrowReleaser releaser = new DeliveredEscrowReleaser(paymentRepository, bidRepository,
                announcementRepository, listener, alertEscalator);

        DeliveredEscrowReleaser.LateRelease result;
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn("succeeded");
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            piStatic.when(() -> PaymentIntent.retrieve(anyString(), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenReturn(pi);
            result = releaser.releaseIfDelivered(payment.getId(), "auto-heal-release");
            trStatic.verifyNoInteractions();
        }

        assertThat(result.outcome()).isEqualTo(EscrowReleaseOutcome.TRANSFER_FAILED);
        PaymentEntity after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(after.getEscrowReleasedAt()).isNull();
        assertThat(after.getStripeTransferId()).isNull();
        verify(alertEscalator).raiseOnce(eq(DeliveredEscrowReleaser.FAILED_ALERT_PREFIX + payment.getId()), anyString(), anyMap());
    }

    @Test
    void claimConditionnel_refuseSiUneGardeEstPoseeEnBase() {
        for (String guard : List.of("disputed = true", "refunded_amount = 5.00", "payout_held_at = CURRENT_TIMESTAMP")) {
            Integer claimed = tx.execute(s -> {
                jdbc.update("UPDATE payments SET " + guard + " WHERE id = ?", payment.getId());
                int n = paymentRepository.markReleasedIfEscrowAndUnguarded(payment.getId(), java.time.LocalDateTime.now());
                s.setRollbackOnly();
                return n;
            });
            assertThat(claimed).as(guard).isZero();
        }
        Integer free = tx.execute(s -> {
            int n = paymentRepository.markReleasedIfEscrowAndUnguarded(payment.getId(), java.time.LocalDateTime.now());
            int reverted = paymentRepository.revertReleaseClaim(payment.getId());
            assertThat(reverted).isEqualTo(1);
            s.setRollbackOnly();
            return n;
        });
        assertThat(free).isEqualTo(1);
    }
}
