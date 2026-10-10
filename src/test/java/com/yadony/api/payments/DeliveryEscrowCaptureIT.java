package com.yadony.api.payments;

import com.stripe.exception.InvalidRequestException;
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
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Livraison d'un paiement de négociation passé ESCROW sans capture (constat du 10/10 : PI
 * {@code requires_capture}, {@code captured_at} nul), base H2 réelle et Stripe simulé.
 *
 * <p>Le listener est construit à la main (appeler le bean passerait par {@code @Async} sur un
 * autre thread, hors des mocks statiques Stripe) mais avec le VRAI {@link EscrowCaptureService}
 * (transaction {@code REQUIRES_NEW}) et les vrais repositories ; l'appel est enveloppé dans une
 * transaction comme en production ({@code REQUIRES_NEW} du listener). Les lignes sont commitées
 * pour que la capture, dans sa propre transaction, les voie.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeliveryEscrowCaptureIT {

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

    private DeliveryEventListener listener;
    private TransactionTemplate tx;
    private BidEntity bid;
    private PaymentEntity payment;
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new DeliveryEventListener(paymentRepository, userRepository, auditService, eventPublisher,
                bidRepository, mock(AdminAlertService.class), voucherService, null, holdPolicy, alertEscalator,
                escrowCapture);
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        UUID threadId = UUID.randomUUID();
        BidEntity b = new BidEntity();
        b.setAnnouncementId(UUID.randomUUID());
        b.setSenderId(UUID.randomUUID());
        b.setStatus(BidStatus.ARRIVED);
        b.setLinkedNegotiationThreadId(threadId);
        bid = bidRepository.saveAndFlush(b);

        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(threadId);  // bid_id NULL : paiement de négociation
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("64.50"));
        p.setCommissionAmount(new BigDecimal("6.91"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);   // passé ESCROW par le checkout d'avant #472, jamais capturé
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

    private void deliver() {
        tx.executeWithoutResult(s -> listener.handleDeliveryConfirmed(
                new DeliveryConfirmedEvent(bid.getId(), bid.getSenderId(), travelerId)));
    }

    private PaymentEntity reload() {
        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    private PaymentIntent intent(String status) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn(status);
        when(pi.getAmount()).thenReturn(6450L);
        when(pi.getAmountCapturable()).thenReturn("requires_capture".equals(status) ? 6450L : 0L);
        when(pi.getCurrency()).thenReturn("eur");
        when(pi.getLatestCharge()).thenReturn("ch_nego");
        return pi;
    }

    private void stubRetrieve(MockedStatic<PaymentIntent> mocked, PaymentIntent... sequence) {
        var stub = mocked.when(() -> PaymentIntent.retrieve(eq(payment.getStripePaymentIntentId()),
                any(PaymentIntentRetrieveParams.class), isNull()));
        stub.thenReturn(sequence[0], java.util.Arrays.copyOfRange(sequence, 1, sequence.length));
    }

    @Test
    void requiresCapture_capturesThenTransfers_andReplayDoesNothing() throws Exception {
        List<String> calls = new ArrayList<>();
        PaymentIntent authorized = intent("requires_capture");
        when(authorized.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            calls.add("capture:" + ((RequestOptions) inv.getArgument(1)).getIdempotencyKey()
                    + ":" + ((PaymentIntentCaptureParams) inv.getArgument(0)).getAmountToCapture());
            return authorized;
        });
        PaymentIntent captured = intent("succeeded");

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            stubRetrieve(piStatic, authorized, captured);
            trStatic.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenAnswer(inv -> {
                        TransferCreateParams params = inv.getArgument(0);
                        calls.add("transfer:" + params.getAmount() + ":" + params.getSourceTransaction());
                        return mock(Transfer.class);
                    });

            deliver();
            // Rejeu de l'événement de livraison : le paiement n'est plus ESCROW, rien ne repart.
            deliver();
        }

        assertThat(calls).containsExactly(
                "capture:capture-" + payment.getId() + ":6450",
                "transfer:5759:ch_nego");
        PaymentEntity after = reload();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(after.getCapturedAt()).isNotNull();
        assertThat(after.getStripeChargeId()).isEqualTo("ch_nego");
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void alreadyCaptured_onlyTransfers() throws Exception {
        PaymentIntent captured = intent("succeeded");
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            stubRetrieve(piStatic, captured);
            trStatic.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(mock(Transfer.class));

            deliver();

            trStatic.verify(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)));
        }
        verify(captured, never()).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
        assertThat(reload().getStatus()).isEqualTo(PaymentStatus.RELEASED);
        assertThat(reload().getCapturedAt()).isNotNull();
    }

    @Test
    void captureRefused_noTransfer_paymentStaysEscrowUncaptured_andAdminAlerted() throws Exception {
        PaymentIntent authorized = intent("requires_capture");
        when(authorized.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class)))
                .thenThrow(new InvalidRequestException("authorization expired", null, "req", null, 400, null));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            stubRetrieve(piStatic, authorized);

            deliver();

            trStatic.verifyNoInteractions();
        }
        PaymentEntity after = reload();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        // La garde markCapturedIfEscrow a été annulée avec la transaction de capture.
        assertThat(after.getCapturedAt()).isNull();
        verify(alertEscalator).raiseOnce(eq("ESCROW_CAPTURE_FAILED_" + payment.getId()), anyString(), anyMap());
        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'DELIVERY_RELEASE_BLOCKED_CAPTURE_FAILED'",
                Integer.class, payment.getId());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void transferFailsAfterCapture_captureIsKept_paymentStaysEscrow() throws Exception {
        PaymentIntent authorized = intent("requires_capture");
        when(authorized.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class))).thenReturn(authorized);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            stubRetrieve(piStatic, authorized);
            trStatic.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("insufficient funds", null, "req", null, 400, null));

            assertThatThrownBy(this::deliver).isInstanceOf(IllegalStateException.class);
        }
        PaymentEntity after = reload();
        // Claim annulé (ESCROW, nouvelle tentative possible), mais la capture faite chez Stripe
        // reste tracée : la livraison rejouée ne capturera pas une seconde fois.
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        assertThat(after.getCapturedAt()).isNotNull();
    }
}
