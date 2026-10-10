package com.yadony.api.payments;

import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
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
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Colis livré alors que son paiement carte était encore PENDING (staging, paiement d9f1fa40 du
 * 04/10/2026), puis passage en séquestre tardif : le versement au voyageur part tout seul, par le
 * webhook tardif, par la resynchronisation admin ou par le job d'auto-réparation.
 *
 * <p>Base H2 réelle, Stripe simulé (mocks statiques, sur le thread du test). Les composants sont
 * construits à la main autour des vrais repositories et du vrai {@link EscrowCaptureService},
 * comme {@link DeliveryEscrowCaptureIT} ; la transaction {@code REQUIRES_NEW} du versement est
 * reproduite par une sous-classe qui l'enveloppe.
 */
@SpringBootTest
@ActiveProfiles("test")
class LateEscrowReleaseIT {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private EscrowCaptureService escrowCapture;
    @Autowired private AuditService auditService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AdminAlertEscalator alertEscalator;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PayoutHoldPolicy holdPolicy = mock(PayoutHoldPolicy.class);
    private final CommissionVoucherService voucherService = mock(CommissionVoucherService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);

    private TransactionTemplate tx;
    private DeliveryEventListener listener;
    private DeliveredEscrowReleaser releaser;
    private PaymentStripeResyncService resync;
    private BidEntity bid;
    private PaymentEntity payment;
    private final UUID travelerId = UUID.randomUUID();
    private final List<String> stripeCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        listener = new DeliveryEventListener(paymentRepository, userRepository, auditService, eventPublisher,
                bidRepository, mock(AdminAlertService.class), voucherService, null, holdPolicy, alertEscalator,
                escrowCapture) {
            @Override
            public EscrowReleaseOutcome releaseAfterLateEscrow(UUID bidId, UUID senderId, UUID travelerId,
                                                               String source) {
                // Ce que fait le proxy Spring en production (REQUIRES_NEW).
                return tx.execute(s -> super.releaseAfterLateEscrow(bidId, senderId, travelerId, source));
            }
        };
        releaser = new DeliveredEscrowReleaser(paymentRepository, bidRepository, announcementRepository, listener);
        resync = new PaymentStripeResyncService(paymentRepository, paymentService, escrowCapture, bidRepository,
                auditService, releaser, transactionManager);

        UUID announcementId = UUID.randomUUID();
        AnnouncementEntity announcement = mock(AnnouncementEntity.class);
        when(announcement.getTravelerId()).thenReturn(travelerId);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        UUID threadId = UUID.randomUUID();
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcementId);
        b.setSenderId(UUID.randomUUID());
        b.setStatus(BidStatus.COMPLETED);           // livré : code de retrait validé
        b.setLinkedNegotiationThreadId(threadId);   // colis créé depuis le fil de négociation
        bid = bidRepository.saveAndFlush(b);

        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(threadId);         // bid_id NULL : paiement porté par le fil
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("50.00"));
        p.setCommissionAmount(new BigDecimal("2.38"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.PENDING);         // webhook amount_capturable_updated jamais reçu
        payment = paymentRepository.saveAndFlush(p);

        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_traveler");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payments WHERE id = ?", payment.getId());
        jdbc.update("DELETE FROM bids WHERE id = ?", bid.getId());
    }

    private PaymentEntity reload() {
        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    private PaymentIntent intent(String status) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getId()).thenReturn(payment.getStripePaymentIntentId());
        when(pi.getStatus()).thenReturn(status);
        when(pi.getAmount()).thenReturn(5000L);
        when(pi.getAmountCapturable()).thenReturn("requires_capture".equals(status) ? 5000L : 0L);
        when(pi.getCurrency()).thenReturn("eur");
        when(pi.getLatestCharge()).thenReturn("ch_late");
        return pi;
    }

    /** Stripe simulé : relectures successives du PaymentIntent, capture et Transfer tracés. */
    private void withStripe(Runnable body, PaymentIntent... reads) throws Exception {
        for (PaymentIntent pi : reads) {
            if ("requires_capture".equals(pi.getStatus())) {
                when(pi.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
                    stripeCalls.add("capture:" + ((RequestOptions) inv.getArgument(1)).getIdempotencyKey());
                    return pi;
                });
            }
        }
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            piStatic.when(() -> PaymentIntent.retrieve(eq(payment.getStripePaymentIntentId()),
                            any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenReturn(reads[0], java.util.Arrays.copyOfRange(reads, 1, reads.length));
            trStatic.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenAnswer(inv -> {
                        TransferCreateParams params = inv.getArgument(0);
                        stripeCalls.add("transfer:" + params.getAmount() + ":" + params.getDestination() + ":"
                                + ((RequestOptions) inv.getArgument(1)).getIdempotencyKey());
                        return mock(Transfer.class);
                    });
            body.run();
        }
    }

    private void deliverWhilePending() {
        tx.executeWithoutResult(s -> listener.handleDeliveryConfirmed(
                new DeliveryConfirmedEvent(bid.getId(), bid.getSenderId(), travelerId)));
        assertThat(reload().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(stripeCalls).isEmpty();
        // Alerte au type raccourci (56 caractères) : elle part, au lieu de lever une exception.
        verify(alertEscalator).raiseOnce(eq("DELIVERY_NOT_ESCROW_" + payment.getId()), anyString(), anyMap());
    }

    private void markEscrowAndCommit() {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE payments SET status = 'ESCROW', stripe_charge_id = 'ch_late' WHERE id = ?", payment.getId()));
    }

    private void assertReleasedOnce() {
        assertThat(stripeCalls).containsExactly(
                "capture:capture-" + payment.getId(),
                "transfer:4762:acct_traveler:transfer-" + payment.getId());
        PaymentEntity after = reload();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(after.getCapturedAt()).isNotNull();
        assertThat(after.getEscrowReleasedAt()).isNotNull();
        verify(alertEscalator).resolveOpen("DELIVERY_NOT_ESCROW_" + payment.getId());
    }

    private int audits(String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?",
                Integer.class, payment.getId(), action);
        return n == null ? 0 : n;
    }

    @Test
    void lateWebhook_afterDelivery_releasesToTheTravelerWithoutAdmin() throws Exception {
        deliverWhilePending();

        // Webhook amount_capturable_updated tardif : PENDING → ESCROW commité, puis l'écouteur.
        markEscrowAndCommit();
        withStripe(() -> releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, payment.getId())),
                intent("requires_capture"), intent("succeeded"));

        assertReleasedOnce();
        assertThat(audits("ESCROW_RELEASED_TRANSFER")).isEqualTo(1);

        // Rejeu de l'événement : plus en séquestre, rien ne repart.
        withStripe(() -> releaser.onEscrowReady(new PaymentEscrowReadyEvent(null, payment.getId())),
                intent("succeeded"));
        assertThat(stripeCalls).hasSize(2);
    }

    @Test
    void adminResync_afterDelivery_activatesCapturesAndReleases() throws Exception {
        deliverWhilePending();
        PaymentIntent authorized = intent("requires_capture");
        when(authorized.getMetadata()).thenReturn(Map.of());
        doAnswer(inv -> {
            jdbc.update("UPDATE payments SET status = 'ESCROW' WHERE id = ?", payment.getId());
            return null;
        }).when(paymentService).applyPaymentEscrowActive(any(PaymentIntent.class), eq(true));

        PaymentStripeResyncService.Result[] result = new PaymentStripeResyncService.Result[1];
        withStripe(() -> result[0] = resync.resync(payment.getId(), UUID.randomUUID()),
                authorized, authorized, intent("succeeded"));

        PaymentStripeResyncService.Result r = result[0];
        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.ESCROW_ACTIVATED);
        assertThat(r.released()).isTrue();
        assertThat(r.after().status()).isEqualTo("RELEASED");
        assertThat(r.message()).contains("Colis déjà livré : versement envoyé au voyageur");
        assertReleasedOnce();
        assertThat(audits("ADMIN_PAYMENT_RESYNC_STRIPE")).isEqualTo(1);
    }

    @Test
    void autoHealJob_picksThePendingPayment_andReleasesIt() throws Exception {
        deliverWhilePending();
        PaymentIntent authorized = intent("requires_capture");
        when(authorized.getMetadata()).thenReturn(Map.of());
        doAnswer(inv -> {
            jdbc.update("UPDATE payments SET status = 'ESCROW' WHERE id = ?", payment.getId());
            return null;
        }).when(paymentService).applyPaymentEscrowActive(any(PaymentIntent.class), eq(true));
        PendingCardPaymentAutoHealJob job = new PendingCardPaymentAutoHealJob(paymentRepository, resync, true,
                Duration.ZERO, Duration.ofDays(7), Duration.ofHours(24), 200,
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5)));

        withStripe(job::run, authorized, authorized, intent("succeeded"));

        assertReleasedOnce();
        Integer systemAudits = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ? "
                + "AND action = 'PAYMENT_AUTO_RESYNC_STRIPE' AND actor_id IS NULL", Integer.class, payment.getId());
        assertThat(systemAudits).isEqualTo(1);
    }
}
