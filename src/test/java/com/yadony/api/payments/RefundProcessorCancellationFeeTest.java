package com.yadony.api.payments;

import com.stripe.exception.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.currency.ExchangeRateLookup;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.fee.CancellationFeeBreakdown;
import com.yadony.api.payments.fee.CancellationFeeRatePort;
import com.yadony.api.payments.fee.CancellationFeeRetainedEvent;
import com.yadony.api.payments.fee.CancellationFeeSettlement;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.wallet.fees.StripeFeeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Remboursement d'un expéditeur fautif sur le rail carte : montant payé moins le dédommagement du
 * voyageur quand une retenue est due et que le PaymentIntent est capturé ; remboursement intégral,
 * inchangé, partout ailleurs (la retenue est alors close {@code NOT_APPLICABLE}). Vrai
 * {@link CancellationFeeSettlement}, port et sources de frais simulés, Stripe simulé par mocks statiques.
 */
@ExtendWith(MockitoExtension.class)
class RefundProcessorCancellationFeeTest {

    private static final String PI = "pi_fee";
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID TRAVELER = UUID.randomUUID();

    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlert;
    @Mock CancellationFeeRatePort feePort;
    @Mock StripeFeeSource stripeFees;
    @Mock CommissionRateResolver commissionRates;
    @Mock ExchangeRateLookup rateLookup;
    @Mock ApplicationEventPublisher events;
    @Mock AdminAlertEscalator alerts;

    private RefundProcessor processor;
    private UUID paymentId;
    private UUID bidId;

    @BeforeEach
    void setUp() {
        CancellationFeeSettlement settlement = new CancellationFeeSettlement(feePort, mock(BidRepository.class),
                stripeFees, mock(com.yadony.api.payments.wallet.fees.PawapayFeeTable.class), commissionRates, new ExchangeRateService(rateLookup), events);
        processor = new RefundProcessor(paymentRepository, auditService, adminAlert,
                mock(PawapayOperationService.class), mock(PawapaySubmissionService.class),
                alerts, mock(PlatformTransactionManager.class), settlement);
        paymentId = UUID.randomUUID();
        bidId = UUID.randomUUID();
        lenient().when(feePort.capEur()).thenReturn(new BigDecimal("30"));
        // Le règlement lit le taux sous verrou (dueRateForUpdate) ; ici le port répond comme sa lecture simple.
        org.mockito.Mockito.lenient().when(feePort.dueRateForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> feePort.dueRate(inv.getArgument(0)));
    }

    @Test
    void succeededPI_withDueFee_refundsAmountMinusGross() {
        PaymentEntity payment = escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            refundStatic.when(() -> Refund.create(params.capture(), options.capture())).thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)), times(1));
            assertThat(params.getValue().getPaymentIntent()).isEqualTo(PI);
            assertThat(params.getValue().getAmount()).isEqualTo(7000L);
            assertThat(options.getValue().getIdempotencyKey()).isEqualTo("refund-" + paymentId);
        }

        CancellationFeeBreakdown expected = new CancellationFeeBreakdown(new BigDecimal("30.00"),
                new BigDecimal("1.75"), new BigDecimal("1.41"), new BigDecimal("26.84"), new BigDecimal("70.00"));
        // Taux lu APRÈS le claim ; retenue réglée APRÈS le remboursement Stripe, dans la même transaction.
        InOrder order = inOrder(paymentRepository, feePort, events, auditService);
        order.verify(paymentRepository).markRefundedIfEscrow(paymentId);
        order.verify(feePort).dueRate(bidId);
        order.verify(paymentRepository).recordRefundedAmount(paymentId, new BigDecimal("70.00"));
        order.verify(feePort).markRetained(bidId, expected, "EUR");
        order.verify(events).publishEvent(new CancellationFeeRetainedEvent(bidId));
        order.verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("PAYMENT_REFUNDED_BID_REJECTED"),
                eq(bidId), anyMap());
        verify(feePort, never()).markNotApplicable(any(), any());
        assertThat(auditPayload()).containsEntry("refundedAmount", "70.00").containsEntry("cancellationFee", "30.00");
    }

    @Test
    void succeededPI_noFee_refundsFull() {
        escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.empty());

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            refundStatic.when(() -> Refund.create(params.capture(), options.capture())).thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            assertThat(params.getValue().getAmount()).as("remboursement intégral : aucun montant").isNull();
            assertThat(options.getValue().getIdempotencyKey()).isEqualTo("refund-" + paymentId);
        }
        verify(paymentRepository, never()).recordRefundedAmount(any(), any());
        verify(feePort, never()).markRetained(any(), any(), any());
        verify(feePort, never()).markNotApplicable(any(), any());
        verifyNoInteractions(stripeFees, commissionRates, events);
        assertThat(auditPayload()).doesNotContainKeys("refundedAmount", "cancellationFee");
    }

    @Test
    void canceledPI_noop() throws Exception {
        escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent canceled = intent("canceled");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(canceled);

            assertThat(refund()).isTrue();

            refundStatic.verifyNoInteractions();
            verify(canceled, never()).cancel(any(PaymentIntentCancelParams.class));
        }
        // Rien n'a été encaissé : une retenue due est close, rien n'est retenu ni versé.
        verify(feePort).markNotApplicable(bidId, "PAYMENT_INTENT_CANCELED");
        verify(feePort, never()).markRetained(any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    void replay_doesNotRefundTwice() {
        escrow("100.00", "4.76", "EUR");
        // Premier passage : claim gagné. Rejeu (même événement relivré) : le paiement est déjà sorti
        // d'ESCROW, le claim répond 0.
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1, 0);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();
            assertThat(refund()).isFalse();

            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)), times(1));
        }
        verify(feePort, times(1)).dueRate(bidId);
        verify(feePort, times(1)).markRetained(eq(bidId), any(), eq("EUR"));
        verify(events, times(1)).publishEvent(any(CancellationFeeRetainedEvent.class));
        verify(paymentRepository, times(1)).recordRefundedAmount(any(), any());
    }

    @Test
    void requiresCapture_withDueFee_cancelsWholeAndMarksNotApplicable() throws Exception {
        escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent authorized = intent("requires_capture");
            when(authorized.cancel(any(PaymentIntentCancelParams.class))).thenReturn(authorized);
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(authorized);

            assertThat(refund()).isTrue();

            verify(authorized).cancel(any(PaymentIntentCancelParams.class));
            refundStatic.verifyNoInteractions();
        }
        verify(feePort).markNotApplicable(bidId, "PAYMENT_NOT_CAPTURED");
        verify(feePort, never()).markRetained(any(), any(), any());
        verify(paymentRepository, never()).recordRefundedAmount(any(), any());
        verifyNoInteractions(stripeFees, events);
    }

    @Test
    void requiresCapture_noFee_cancelsWholeWithoutClosingAnything() throws Exception {
        escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.empty());

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent authorized = intent("requires_capture");
            when(authorized.cancel(any(PaymentIntentCancelParams.class))).thenReturn(authorized);
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(authorized);

            assertThat(refund()).isTrue();
        }
        verify(feePort, never()).markNotApplicable(any(), any());
        verifyNoInteractions(stripeFees, events);
        assertThat(auditPayload()).doesNotContainKeys("refundedAmount", "cancellationFee");
    }

    @Test
    void legacyDestinationCharge_withDueFee_refundsFullAndMarksNotApplicable() {
        PaymentEntity payment = escrow("100.00", "4.76", "EUR");
        payment.setLegacyDestinationCharge(true);
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            assertThat(params.getValue().getAmount()).isNull();
        }
        verify(feePort).markNotApplicable(bidId, "LEGACY_DESTINATION_CHARGE");
        verify(feePort, never()).markRetained(any(), any(), any());
        verifyNoInteractions(stripeFees, events);
    }

    @Test
    void stripeFailure_keepsFeeDue_andRollsBack() {
        escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiException("boom", null, null, 500, null));

            // L'exception fait annuler la transaction REQUIRES_NEW : claim défait, paiement remboursable.
            assertThatThrownBy(this::refund).isInstanceOf(IllegalStateException.class);
        }
        verify(adminAlert).raise(eq("STRIPE_REFUND_FAILED"), any(), anyMap());
        // Rien n'est réglé : la retenue reste DUE pour le prochain essai.
        verify(feePort, never()).markRetained(any(), any(), any());
        verify(feePort, never()).markNotApplicable(any(), any());
        verify(paymentRepository, never()).recordRefundedAmount(any(), any());
        verifyNoInteractions(events, auditService);
    }

    @Test
    void xofPayment_capConvertedAndAmountsInteger() {
        escrow("100000", "4762", "XOF");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(rateLookup.unitsPerEur("XOF")).thenReturn(new BigDecimal("655.957"));
        when(stripeFees.fee(PI, "XOF")).thenReturn(null);
        when(stripeFees.fallback(new BigDecimal("100000"), "XOF")).thenReturn(new BigDecimal("3150"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            // XOF sans décimale : 100 000 − 19 678 (30 € arrondis vers le bas), en unités entières.
            assertThat(params.getValue().getAmount()).isEqualTo(80322L);
        }
        ArgumentCaptor<CancellationFeeBreakdown> retained = ArgumentCaptor.forClass(CancellationFeeBreakdown.class);
        verify(feePort).markRetained(eq(bidId), retained.capture(), eq("XOF"));
        CancellationFeeBreakdown fee = retained.getValue();
        assertThat(fee.grossFee()).isEqualByComparingTo("19678");
        assertThat(fee.grossFee().divide(new BigDecimal("655.957"), 4, java.math.RoundingMode.HALF_UP))
                .isLessThanOrEqualTo(new BigDecimal("30"));
        for (BigDecimal amount : new BigDecimal[]{fee.grossFee(), fee.refundFees(), fee.commission(),
                fee.travelerNet(), fee.senderRefund()}) {
            assertThat(amount.scale()).isZero();
        }
        assertThat(fee.travelerNet()).isEqualByComparingTo("15702");
        verify(paymentRepository).recordRefundedAmount(paymentId, new BigDecimal("80322"));
    }

    @Test
    void succeededPI_noCommissionSnapshot_resolvesTheRateOfThePaymentIntentParties() {
        escrow("100.00", null, "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));
        when(commissionRates.resolve(TRAVELER, SENDER)).thenReturn(new BigDecimal("0.05"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();
        }
        verify(feePort).markRetained(bidId, new CancellationFeeBreakdown(new BigDecimal("30.00"),
                new BigDecimal("1.75"), new BigDecimal("1.41"), new BigDecimal("26.84"), new BigDecimal("70.00")), "EUR");
    }

    @Test
    void succeededPI_unreadablePartiesMetadata_stillRetainsWithTheResolverFallback() {
        escrow("100.00", null, "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));
        when(commissionRates.resolve(null, null)).thenReturn(new BigDecimal("0.05"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = mock(PaymentIntent.class);
            when(captured.getStatus()).thenReturn("succeeded");
            // Expéditeur absent, voyageur illisible : la retenue n'en dépend pas, seul le repli du taux.
            when(captured.getMetadata()).thenReturn(Map.of("traveler_id", "not-a-uuid"));
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();
        }
        verify(feePort).markRetained(eq(bidId), any(CancellationFeeBreakdown.class), eq("EUR"));
    }

    @Test
    void computationFailure_missingExchangeRate_refundsFullAndRaisesAlert() {
        // Devise sans ligne exchange_rates : la conversion du plafond lève. L'expéditeur est remboursé
        // en entier quand même, jamais bloqué par la retenue.
        escrow("100000", "4762", "XOF");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
        when(rateLookup.unitsPerEur("XOF")).thenThrow(YadonyBusinessException.localized(
                HttpStatus.UNPROCESSABLE_ENTITY, ExchangeRateService.RATE_MISSING_CODE,
                "problem.exchange-rate-missing", Map.of("currency", "XOF"), "XOF"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            refundStatic.when(() -> Refund.create(params.capture(), options.capture())).thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            assertThat(params.getValue().getAmount()).as("remboursement intégral").isNull();
            assertThat(options.getValue().getIdempotencyKey()).isEqualTo("refund-" + paymentId);
        }
        String type = RefundProcessor.FEE_COMPUTE_FAILED_ALERT_PREFIX + paymentId;
        assertThat(type.length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
        verify(alerts).raiseOnce(eq(type), contains("remboursé en entier"), anyMap());
        verify(feePort).markNotApplicable(bidId, "FEE_COMPUTATION_FAILED");
        verify(feePort, never()).markRetained(any(), any(), any());
        verify(paymentRepository, never()).recordRefundedAmount(any(), any());
        verifyNoInteractions(events);
        assertThat(auditPayload()).doesNotContainKeys("refundedAmount", "cancellationFee");
    }

    @Test
    void computationFailure_invalidRate_refundsFull() {
        // Barème non validé : un taux au-delà de 1 fait lever le calculateur. Même une alerte en échec
        // ne remet pas en cause le remboursement.
        escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("1.5000")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));
        when(alerts.raiseOnce(any(), any(), anyMap())).thenThrow(new IllegalStateException("alertes indisponibles"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(params.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Refund.class));

            assertThat(refund()).isTrue();

            assertThat(params.getValue().getAmount()).isNull();
        }
        verify(feePort).markNotApplicable(bidId, "FEE_COMPUTATION_FAILED");
        verify(feePort, never()).markRetained(any(), any(), any());
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("PAYMENT_REFUNDED_BID_REJECTED"), eq(bidId), anyMap());
    }

    /**
     * Base en échec pendant le règlement (attente du verrou de la retenue, lecture, transaction) : jamais le repli
     * « remboursement intégral ». La transaction du claim est perdue : aucun appel à Refund.create, l'exception remonte
     * telle quelle et défait le claim (le paiement reste ESCROW, la retenue due), comme un échec Stripe.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("databaseFailures")
    void databaseFailureDuringTheSettlement_propagates_noStripeRefund(RuntimeException failure) {
        escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRateForUpdate(bidId)).thenThrow(failure);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);

            assertThatThrownBy(this::refund).isSameAs(failure);

            refundStatic.verifyNoInteractions();
        }
        verifyNoInteractions(alerts, adminAlert, auditService, events);
        verify(feePort, never()).markNotApplicable(any(), any());
        verify(feePort, never()).markRetained(any(), any(), any());
        verify(paymentRepository, never()).recordRefundedAmount(any(), any());
    }

    static java.util.stream.Stream<RuntimeException> databaseFailures() {
        return java.util.stream.Stream.of(
                new org.springframework.dao.CannotAcquireLockException("lock wait timeout on cancellation_fees"),
                new org.springframework.dao.DataAccessResourceFailureException("connection lost"),
                new org.springframework.transaction.TransactionTimedOutException("transaction timed out"),
                new jakarta.persistence.PessimisticLockException("could not obtain the row lock"));
    }

    @Test
    void computationFailure_thenStripeFailure_keepsTheStripeFailureBehaviour() {
        escrow("100.00", "4.76", "EUR");
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("1.5000")));
        when(stripeFees.fee(PI, "EUR")).thenReturn(new BigDecimal("1.75"));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent captured = intent("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve(PI)).thenReturn(captured);
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiException("boom", null, null, 500, null));

            assertThatThrownBy(this::refund).isInstanceOf(IllegalStateException.class);
        }
        // Échec Stripe inchangé : alerte STRIPE_REFUND_FAILED, exception (rollback du claim), retenue due.
        verify(adminAlert).raise(eq("STRIPE_REFUND_FAILED"), any(), anyMap());
        verifyNoInteractions(alerts);
        verify(feePort, never()).markNotApplicable(any(), any());
        verifyNoInteractions(auditService);
    }

    private boolean refund() {
        return processor.processRefund(paymentId, "PAYMENT_REFUNDED_BID_REJECTED", bidId,
                Map.of("reason", "bid_rejected"));
    }

    private PaymentEntity escrow(String amount, String commission, String currency) {
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", paymentId);
        p.setBidId(bidId);
        p.setStripePaymentIntentId(PI);
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(commission == null ? null : new BigDecimal(commission));
        p.setCurrency(currency);
        p.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        return p;
    }

    private static PaymentIntent intent(String status) {
        PaymentIntent pi = mock(PaymentIntent.class);
        when(pi.getStatus()).thenReturn(status);
        lenient().when(pi.getMetadata()).thenReturn(Map.of(
                "sender_id", SENDER.toString(), "traveler_id", TRAVELER.toString()));
        return pi;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditPayload() {
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("PAYMENT"), any(), any(), any(), payload.capture());
        return payload.getValue();
    }
}
