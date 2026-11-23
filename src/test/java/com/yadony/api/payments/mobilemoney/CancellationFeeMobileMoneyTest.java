package com.yadony.api.payments.mobilemoney;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.RefundProcessor;
import com.yadony.api.payments.StripeTransferLookup;
import com.yadony.api.payments.currency.ExchangeRateLookup;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.fee.CancellationFeeBreakdown;
import com.yadony.api.payments.fee.CancellationFeePayoutPort;
import com.yadony.api.payments.fee.CancellationFeePayoutService;
import com.yadony.api.payments.fee.CancellationFeeRatePort;
import com.yadony.api.payments.fee.CancellationFeeRetainedEvent;
import com.yadony.api.payments.fee.CancellationFeeSettlement;
import com.yadony.api.payments.fee.PayableCancellationFee;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldReason;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.wallet.fees.PawapayFeeTable;
import com.yadony.api.payments.wallet.fees.StripeFeeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Frais d'annulation sur le rail mobile money (pawaPay) : remboursement partiel du dépôt à l'expéditeur, retenue
 * réglée, puis versement pawaPay du net au voyageur rattaché à la retenue.
 *
 * <p><b>Vérification préalable (tâche 7, étape 1) : le remboursement PARTIEL pawaPay est possible.</b> Documentation
 * pawaPay v2, page « Refunds » (docs.pawapay.io/v2/docs/refunds, lue le 21/11/2026) : la requête porte
 * {@code amount} et {@code currency} ; plusieurs remboursements partiels d'un même dépôt sont admis tant que leur
 * total ne dépasse pas le dépôt ({@code AMOUNT_TOO_LARGE} au-delà) ; un seul remboursement en cours à la fois par
 * dépôt ({@code REFUND_IN_PROGRESS}). Dans le code : {@code PawapayClient#initiateRefund} envoie déjà
 * {@code amount}, et {@code WalletPawapayRefundIssuer} rembourse déjà un net partiel (spec
 * 2026-09-17-wallet-recharge-mobile-money, §1, même vérification). Le rail rembourse donc {@code senderRefund}
 * (payé − brut retenu) au lieu du dépôt entier.
 *
 * <p>Exemple chiffré (XOF, échelle 0) : 65 600 F payés dont 3 124 F de commission (taux effectif 0,050003), retenue
 * de 30 % = 19 680 F plafonnée à 30 € = 19 678 F (19 678,71 arrondi vers le bas) ; rendu à l'expéditeur 45 922 F ;
 * frais pawaPay ORANGE_SEN (dépôt 1,5 %, remboursement 1 %) = 984 + 459,22 = 1 443 F ; commission 912 F ; net
 * voyageur 17 323 F.
 */
class CancellationFeeMobileMoneyTest {

    private static final BigDecimal XOF_PER_EUR = new BigDecimal("655.957");

    private static PawapayFeeTable feeTable() {
        return new PawapayFeeTable(new PawapayFeeTable.Rate(new BigDecimal("2.5"), new BigDecimal("2.0")),
                Map.of("ORANGE_SEN", new PawapayFeeTable.Rate(new BigDecimal("1.5"), new BigDecimal("1.0"))));
    }

    private static boolean same(BigDecimal actual, String expected) {
        return actual != null && actual.compareTo(new BigDecimal(expected)) == 0;
    }

    /** Échecs de la base qui ne doivent jamais tomber dans le repli du calcul (RefundProcessor#mustPropagate). */
    static java.util.stream.Stream<RuntimeException> databaseFailures() {
        return java.util.stream.Stream.of(
                new org.springframework.dao.CannotAcquireLockException("lock wait timeout on cancellation_fees"),
                new org.springframework.transaction.TransactionTimedOutException("transaction timed out"),
                new jakarta.persistence.PessimisticLockException("could not obtain the row lock"));
    }

    // ── Remboursement de l'expéditeur (RefundProcessor, rail PAWAPAY) ────────────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class SenderRefund {

        @Mock PaymentRepository paymentRepository;
        @Mock AuditService auditService;
        @Mock AdminAlertService adminAlert;
        @Mock PawapayOperationService operations;
        @Mock PawapaySubmissionService submission;
        @Mock AdminAlertEscalator alerts;
        @Mock PlatformTransactionManager transactionManager;
        @Mock CancellationFeeRatePort feePort;
        @Mock StripeFeeSource stripeFees;
        @Mock CommissionRateResolver commissionRates;
        @Mock ExchangeRateLookup rateLookup;
        @Mock ApplicationEventPublisher events;

        private RefundProcessor processor;
        private PaymentEntity payment;
        private UUID paymentId;
        private UUID bidId;
        private PawapayOperationEntity deposit;

        @BeforeEach
        void setUp() {
            CancellationFeeSettlement settlement = new CancellationFeeSettlement(feePort, mock(BidRepository.class),
                    stripeFees, feeTable(), commissionRates, new ExchangeRateService(rateLookup), events);
            processor = new RefundProcessor(paymentRepository, auditService, adminAlert, operations, submission, alerts,
                    transactionManager, settlement);
            paymentId = UUID.randomUUID();
            bidId = UUID.randomUUID();
            payment = new PaymentEntity();
            ReflectionTestUtils.setField(payment, "id", paymentId);
            payment.setBidId(bidId);
            payment.setRail(PaymentRail.PAWAPAY);
            payment.setStatus(PaymentStatus.ESCROW);
            payment.setAmount(new BigDecimal("65600"));
            payment.setCommissionAmount(new BigDecimal("3124"));
            payment.setCurrency("XOF");
            deposit = operation(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED, "65600", "ORANGE_SEN");

            when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
            when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
            when(operations.findLive(paymentId, PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
            when(operations.findLive(paymentId, PawapayOperationKind.REFUND)).thenReturn(Optional.empty());
            when(operations.findLatest(paymentId, PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
            when(feePort.capEur()).thenReturn(new BigDecimal("30"));
            // Le règlement lit le taux sous verrou (dueRateForUpdate) ; ici le port répond comme sa lecture simple.
            org.mockito.Mockito.lenient().when(feePort.dueRateForUpdate(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> feePort.dueRate(inv.getArgument(0)));
            when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("0.30")));
            when(rateLookup.unitsPerEur("XOF")).thenReturn(XOF_PER_EUR);
            when(submission.submitRefund(eq(paymentId), eq(deposit), any())).thenAnswer(call ->
                    operation(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED,
                            call.<BigDecimal>getArgument(2).toPlainString(), "ORANGE_SEN"));
        }

        @Test
        void refundMobileMoney_withDueFee_partialRefund() {
            assertThat(refund()).isTrue();

            // Remboursement PARTIEL : payé − brut (65 600 − 19 678), jamais le dépôt entier.
            verify(submission).submitRefund(eq(paymentId), eq(deposit), argThat(a -> same(a, "45922")));
            verify(paymentRepository).recordRefundedAmount(eq(paymentId), argThat(a -> same(a, "45922")));
            ArgumentCaptor<CancellationFeeBreakdown> breakdown = ArgumentCaptor.forClass(CancellationFeeBreakdown.class);
            verify(feePort).markRetained(eq(bidId), breakdown.capture(), eq("XOF"));
            CancellationFeeBreakdown fee = breakdown.getValue();
            assertThat(fee.grossFee()).isEqualByComparingTo("19678");
            assertThat(fee.refundFees()).isEqualByComparingTo("1443");
            assertThat(fee.commission()).isEqualByComparingTo("912");
            assertThat(fee.travelerNet()).isEqualByComparingTo("17323");
            assertThat(fee.senderRefund()).isEqualByComparingTo("45922");
            // Échelle de la devise : aucun centime de franc CFA.
            assertThat(List.of(fee.grossFee(), fee.refundFees(), fee.commission(), fee.travelerNet(), fee.senderRefund()))
                    .allSatisfy(amount -> assertThat(amount.scale()).isZero());
            // Le plafond de 30 € a bien joué : 30 % de 65 600 = 19 680 > 19 678.
            assertThat(fee.grossFee()).isLessThan(new BigDecimal("19680"));
            verify(events).publishEvent(new CancellationFeeRetainedEvent(bidId));
            verify(feePort, never()).markNotApplicable(any(), any());

            // Ordre : claim, puis lecture du taux (sous le claim), soumission, et seulement ensuite le règlement.
            InOrder order = inOrder(paymentRepository, feePort, submission, events);
            order.verify(paymentRepository).markRefundedIfEscrow(paymentId);
            order.verify(feePort).dueRate(bidId);
            order.verify(submission).submitRefund(eq(paymentId), eq(deposit), any());
            order.verify(paymentRepository).recordRefundedAmount(eq(paymentId), any());
            order.verify(feePort).markRetained(eq(bidId), any(), eq("XOF"));
            order.verify(events).publishEvent(any(CancellationFeeRetainedEvent.class));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
            verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("PAYMENT_REFUNDED_BID_REJECTED"), any(),
                    audit.capture());
            assertThat(audit.getValue()).containsEntry("refundedAmount", "45922").containsEntry("cancellationFee", "19678");
        }

        @Test
        void refundFees_readFromPawapayFeeTable_forTheDepositOperator() {
            // Opérateur sans surcharge : barème par défaut (dépôt 2,5 %, remboursement 2 %), retenue sous le plafond.
            payment.setAmount(new BigDecimal("50000"));
            payment.setCommissionAmount(new BigDecimal("2381"));
            deposit = operation(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED, "50000", "MTN_MOMO_CIV");
            when(operations.findLatest(paymentId, PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
            when(submission.submitRefund(eq(paymentId), eq(deposit), any())).thenAnswer(call ->
                    operation(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED,
                            call.<BigDecimal>getArgument(2).toPlainString(), "MTN_MOMO_CIV"));

            assertThat(refund()).isTrue();

            // Dépôt facturé sur l'encaissé (50 000 × 2,5 % = 1 250), remboursement sur le rendu (35 000 × 2 % = 700).
            ArgumentCaptor<CancellationFeeBreakdown> breakdown = ArgumentCaptor.forClass(CancellationFeeBreakdown.class);
            verify(feePort).markRetained(eq(bidId), breakdown.capture(), eq("XOF"));
            assertThat(breakdown.getValue().grossFee()).isEqualByComparingTo("15000");
            assertThat(breakdown.getValue().refundFees()).isEqualByComparingTo("1950");
            assertThat(breakdown.getValue().commission()).isEqualByComparingTo("653");
            assertThat(breakdown.getValue().travelerNet()).isEqualByComparingTo("12397");
            verify(submission).submitRefund(eq(paymentId), eq(deposit), argThat(a -> same(a, "35000")));
            // Jamais les frais Stripe sur ce rail.
            verifyNoInteractions(stripeFees);
        }

        @Test
        void failOpen_computationError_refundsFullDeposit_closesTheRow_andAlerts() {
            when(rateLookup.unitsPerEur("XOF")).thenThrow(new IllegalStateException("exchange-rate-missing"));

            assertThat(refund()).isTrue();

            verify(submission).submitRefund(eq(paymentId), eq(deposit), argThat(a -> same(a, "65600")));
            verify(alerts).raiseOnce(eq("FEE_COMPUTE_FAILED_" + paymentId), contains("65600"), anyMap());
            verify(feePort).markNotApplicable(bidId, "FEE_COMPUTATION_FAILED");
            verify(feePort, never()).markRetained(any(), any(), any());
            verify(paymentRepository, never()).recordRefundedAmount(any(), any());
            verify(events, never()).publishEvent(any());
        }

        @Test
        void failOpen_invalidRate_refundsFullDeposit() {
            when(feePort.dueRate(bidId)).thenReturn(Optional.of(new BigDecimal("1.5")));

            assertThat(refund()).isTrue();

            verify(submission).submitRefund(eq(paymentId), eq(deposit), argThat(a -> same(a, "65600")));
            verify(feePort).markNotApplicable(bidId, "FEE_COMPUTATION_FAILED");
        }

        /**
         * Base en échec pendant le règlement (attente du verrou de la retenue, lecture, transaction) : jamais le repli
         * « dépôt rendu en entier ». La transaction du claim est perdue : rien n'est soumis à pawaPay, l'exception
         * remonte telle quelle et défait le claim (le paiement reste ESCROW, la retenue due).
         */
        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.MethodSource("com.yadony.api.payments.mobilemoney.CancellationFeeMobileMoneyTest#databaseFailures")
        void databaseFailureDuringTheSettlement_propagates_nothingSubmitted(RuntimeException failure) {
            when(feePort.dueRateForUpdate(bidId)).thenThrow(failure);

            assertThatThrownBy(this::refund).isSameAs(failure);

            verify(submission, never()).submitRefund(any(), any(), any());
            verify(operations, never()).findLive(paymentId, PawapayOperationKind.REFUND);
            verifyNoInteractions(alerts);
            verify(feePort, never()).markNotApplicable(any(), any());
            verify(feePort, never()).markRetained(any(), any(), any());
            verify(paymentRepository, never()).recordRefundedAmount(any(), any());
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }

        @Test
        void providerRejection_keepsTheRowDue_andRollsBack() {
            when(submission.submitRefund(eq(paymentId), eq(deposit), any())).thenAnswer(call -> {
                PawapayOperationEntity rejected = operation(PawapayOperationKind.REFUND,
                        PawapayOperationStatus.SUBMIT_REJECTED, call.<BigDecimal>getArgument(2).toPlainString(),
                        "ORANGE_SEN");
                rejected.setFailureCode("AMOUNT_OUT_OF_BOUNDS");
                return rejected;
            });

            assertThatThrownBy(this::refund).isInstanceOf(IllegalStateException.class);

            verify(submission).submitRefund(eq(paymentId), eq(deposit), argThat(a -> same(a, "45922")));
            verify(alerts).raiseOnce(eq("PAWAPAY_REFUND_REJECTED_" + paymentId), anyString(), anyMap());
            // Rien de réglé ni de clos : l'exception annule le claim (REQUIRES_NEW), la ligne reste DUE.
            verify(feePort, never()).markRetained(any(), any(), any());
            verify(feePort, never()).markNotApplicable(any(), any());
            verify(paymentRepository, never()).recordRefundedAmount(any(), any());
            verify(events, never()).publishEvent(any());
            verify(auditService, never()).log(any(), any(), any(), any(), any());
        }

        @Test
        void noFee_refundsTheWholeDeposit_exactlyAsBefore() {
            when(feePort.dueRate(bidId)).thenReturn(Optional.empty());

            assertThat(refund()).isTrue();

            verify(submission).submitRefund(paymentId, deposit, deposit.getAmount());
            verify(feePort, never()).markRetained(any(), any(), any());
            verify(feePort, never()).markNotApplicable(any(), any());
            verify(paymentRepository, never()).recordRefundedAmount(any(), any());
            verify(events, never()).publishEvent(any());
            verifyNoInteractions(rateLookup, stripeFees, commissionRates);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
            verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("PAYMENT_REFUNDED_BID_REJECTED"), any(),
                    audit.capture());
            assertThat(audit.getValue()).doesNotContainKeys("refundedAmount", "cancellationFee")
                    .containsKeys("refundOperationId", "amount", "rail");
        }

        @Test
        void liveRefundOfTheFeeAmount_isReusedNotResubmitted_andTheFeeRetained() {
            // Tentative précédente soumise puis annulée (réseau muet, commit raté) : son remboursement fait foi.
            PawapayOperationEntity live = operation(PawapayOperationKind.REFUND, PawapayOperationStatus.PROCESSING,
                    "45922", "ORANGE_SEN");
            when(operations.findLive(paymentId, PawapayOperationKind.REFUND)).thenReturn(Optional.of(live));

            assertThat(refund()).isTrue();

            verify(submission, never()).submitRefund(any(), any(), any());
            verify(feePort).markRetained(eq(bidId), argThat(f -> same(f.senderRefund(), "45922")), eq("XOF"));
            verify(paymentRepository).recordRefundedAmount(eq(paymentId), argThat(a -> same(a, "45922")));
        }

        @Test
        void liveRefundOfAnotherAmount_isReused_feeClosedWithAnAlert() {
            PawapayOperationEntity live = operation(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED,
                    "65600", "ORANGE_SEN");
            when(operations.findLive(paymentId, PawapayOperationKind.REFUND)).thenReturn(Optional.of(live));

            assertThat(refund()).isTrue();

            verify(submission, never()).submitRefund(any(), any(), any());
            verify(alerts).raiseOnce(eq("FEE_REFUND_MISMATCH_" + paymentId), anyString(), anyMap());
            verify(feePort).markNotApplicable(bidId, "REFUND_ALREADY_IN_FLIGHT");
            verify(feePort, never()).markRetained(any(), any(), any());
            verify(paymentRepository, never()).recordRefundedAmount(any(), any());
        }

        @Test
        void livePartialRefund_withoutAnyDueFee_amountRecordedAndOneAlert_neverASilentFullRefund() {
            // Revue tâche 7, tour 2 : remboursement partiel d'une tentative annulée, retenue entre-temps close.
            when(feePort.dueRate(bidId)).thenReturn(Optional.empty());
            PawapayOperationEntity live = operation(PawapayOperationKind.REFUND, PawapayOperationStatus.CREATED,
                    "45922", "ORANGE_SEN");
            when(operations.findLive(paymentId, PawapayOperationKind.REFUND)).thenReturn(Optional.of(live));

            assertThat(refund()).isTrue();

            verify(submission, never()).submitRefund(any(), any(), any());
            // Jamais « remboursé en entier » : le montant réellement rendu est enregistré.
            verify(paymentRepository).recordRefundedAmount(eq(paymentId), argThat(a -> same(a, "45922")));
            verify(alerts).raiseOnce(eq("MM_PARTIAL_REFUND_" + paymentId), contains("19678"), anyMap());
            verify(feePort, never()).markRetained(any(), any(), any());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> audit = ArgumentCaptor.forClass(Map.class);
            verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("PAYMENT_REFUNDED_BID_REJECTED"), any(),
                    audit.capture());
            assertThat(audit.getValue()).containsEntry("refundedAmount", "45922");
        }

        @Test
        void livePartialRefundOfAnotherAmount_withADueFee_mismatchAlert_andTheAmountRecorded() {
            PawapayOperationEntity live = operation(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED,
                    "50000", "ORANGE_SEN");
            when(operations.findLive(paymentId, PawapayOperationKind.REFUND)).thenReturn(Optional.of(live));

            assertThat(refund()).isTrue();

            verify(alerts).raiseOnce(eq("FEE_REFUND_MISMATCH_" + paymentId), anyString(), anyMap());
            verify(feePort).markNotApplicable(bidId, "REFUND_ALREADY_IN_FLIGHT");
            verify(paymentRepository).recordRefundedAmount(eq(paymentId), argThat(a -> same(a, "50000")));
            // Une seule alerte pour cet écart : celle de la retenue suffit.
            verify(alerts, never()).raiseOnce(eq("MM_PARTIAL_REFUND_" + paymentId), anyString(), anyMap());
        }

        @Test
        void partialRefundAlertType_fitsTheAdminAlertsColumn() {
            assertThat(("MM_PARTIAL_REFUND_" + UUID.randomUUID()).length())
                    .isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
        }

        @Test
        void depositAmountDiffersFromThePayment_refundsTheWholeDeposit_andClosesTheRow() {
            deposit = operation(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED, "65000", "ORANGE_SEN");
            when(operations.findLatest(paymentId, PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
            when(submission.submitRefund(eq(paymentId), eq(deposit), any())).thenAnswer(call ->
                    operation(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED, "65000", "ORANGE_SEN"));

            assertThat(refund()).isTrue();

            verify(submission).submitRefund(paymentId, deposit, deposit.getAmount());
            verify(feePort).markNotApplicable(bidId, "DEPOSIT_AMOUNT_MISMATCH");
            verify(feePort, never()).markRetained(any(), any(), any());
        }

        @Test
        void alreadyPartiallyRefunded_refundsTheWholeDeposit_andClosesTheRow() {
            payment.setRefundedAmount(new BigDecimal("1000"));

            assertThat(refund()).isTrue();

            verify(submission).submitRefund(paymentId, deposit, deposit.getAmount());
            verify(feePort).markNotApplicable(bidId, "PAYMENT_ALREADY_PARTIALLY_REFUNDED");
        }

        @Test
        void pendingPayment_cancelledWithoutPawapay_closesTheDueFee() {
            payment.setStatus(PaymentStatus.PENDING);
            when(paymentRepository.markCancelledIfPending(paymentId)).thenReturn(1);

            assertThat(refund()).isTrue();

            verifyNoInteractions(submission);
            verify(feePort).markNotApplicable(bidId, "PAYMENT_NOT_CAPTURED");
        }

        @Test
        void refundMismatchAlertType_fitsTheAdminAlertsColumn() {
            assertThat(("FEE_REFUND_MISMATCH_" + UUID.randomUUID()).length())
                    .isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
        }

        private boolean refund() {
            return processor.processRefund(paymentId, "PAYMENT_REFUNDED_BID_REJECTED", null, Map.of("reason", "bid_rejected"));
        }

        private PawapayOperationEntity operation(PawapayOperationKind kind, PawapayOperationStatus status, String amount,
                                                 String provider) {
            PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), kind, paymentId, null,
                    new BigDecimal(amount), "XOF", provider, "SN", "221771234567");
            op.setStatus(status);
            return op;
        }
    }

    // ── Versement du net au voyageur (CancellationFeePayoutService + MobileMoneyPayoutInitiator) ─────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class TravelerPayout {

        private static final String ACCOUNT_MSISDN = "221770000002";

        @Mock CancellationFeePayoutPort port;
        @Mock PaymentRepository payments;
        @Mock UserRepository users;
        @Mock PayoutHoldPolicy holdPolicy;
        @Mock DisputeRepository disputes;
        @Mock AdminAlertEscalator alerts;
        @Mock AdminAlertService adminAlert;
        @Mock AuditService audit;
        @Mock PawapayOperationService operations;
        @Mock PawapaySubmissionService submission;
        @Mock MobileMoneyPayoutAccountRepository accountRepository;
        @Mock PlatformTransactionManager transactionManager;

        private CancellationFeePayoutService service;
        private UUID feeId;
        private UUID bidId;
        private UUID travelerId;
        private PaymentEntity payment;
        private String alertType;

        @BeforeEach
        void setUp() {
            MobileMoneyPayoutInitiator initiator = new MobileMoneyPayoutInitiator(
                    new MobileMoneyPayoutAccounts(accountRepository, TestMessages.resolver()), operations, submission,
                    adminAlert, alerts, audit, transactionManager, holdPolicy, payments);
            service = new CancellationFeePayoutService(port, payments, users, holdPolicy, disputes, alerts, initiator,
                    operations, new StripeTransferLookup(), transactionManager);
            feeId = UUID.randomUUID();
            bidId = UUID.randomUUID();
            travelerId = UUID.randomUUID();
            alertType = CancellationFeePayoutService.ALERT_PREFIX + feeId;

            payment = new PaymentEntity();
            ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
            payment.setBidId(bidId);
            payment.setRail(PaymentRail.PAWAPAY);
            payment.setStatus(PaymentStatus.REFUNDED);
            payment.setAmount(new BigDecimal("65600"));
            payment.setRefundedAmount(new BigDecimal("45922"));
            payment.setCurrency("XOF");

            when(port.findPayable(feeId)).thenReturn(Optional.of(fee("17323")));
            when(port.markPaidOut(eq(feeId), any())).thenReturn(true);
            when(port.markPayoutPending(eq(feeId), anyString())).thenReturn(true);
            when(payments.findForBid(bidId)).thenReturn(Optional.of(payment));
            when(holdPolicy.statusOf(travelerId)).thenReturn(PayoutHoldStatus.NONE);
            when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND))
                    .thenReturn(Optional.of(paymentOperation(PawapayOperationKind.REFUND, PawapayOperationStatus.COMPLETED,
                            "45922")));
            when(operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT))
                    .thenReturn(Optional.of(paymentOperation(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED,
                            "65600")));
            when(operations.findLiveForCancellationFee(feeId)).thenReturn(Optional.empty());
            givenAccounts(account("ORANGE_SEN", "SN", "XOF"));
            when(submission.submitCancellationFeePayout(eq(feeId), eq(travelerId), anyString(), anyString(), anyString(),
                    any(), anyString(), anyString())).thenAnswer(call -> feePayout(PawapayOperationStatus.ACCEPTED,
                    call.<BigDecimal>getArgument(5).toPlainString()));
        }

        @Test
        void payout_submittedToTheTravelerAccount_rowWaitsForTheOutcome() {
            assertThat(service.payOut(feeId)).isFalse();

            verify(submission).submitCancellationFeePayout(eq(feeId), eq(travelerId), eq(ACCOUNT_MSISDN),
                    eq("ORANGE_SEN"), eq("SN"), argThat(a -> same(a, "17323")), eq("XOF"), eq("fee-" + feeId));
            // Accepté n'est pas arrivé : la ligne attend l'issue pawaPay (écouteur), aucune transition ici.
            verify(port, never()).markPaidOut(any(), any());
            verify(port, never()).markPayoutPending(any(), any());
            verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
            verify(audit).log(eq("BID"), eq(bidId), eq("CANCELLATION_FEE_PAYOUT_SUBMITTED"), any(), anyMap());
        }

        @Test
        void travelerWithoutMobileMoneyAccount_staysPending() {
            givenAccounts();

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "NO_MOBILE_MONEY_ACCOUNT");
            verify(alerts).raiseOnce(eq(alertType), anyString(), anyMap());
            verify(submission, never()).submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any());
            // Jamais l'alerte non dédupliquée du versement de colis : une seule alerte, celle de la retenue.
            verifyNoInteractions(adminAlert);
        }

        @Test
        void accountInAnotherCurrency_staysPending() {
            givenAccounts(account("MTN_MOMO_GHA", "GH", "GHS"));

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "NO_MOBILE_MONEY_ACCOUNT");
        }

        @Test
        void replay_reusesTheLiveOperation_noSecondPayout() {
            when(operations.findLiveForCancellationFee(feeId))
                    .thenReturn(Optional.of(feePayout(PawapayOperationStatus.ACCEPTED, "17323")));

            assertThat(service.payOut(feeId)).isFalse();

            verify(submission, never()).submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any());
            verify(port, never()).markPayoutPending(any(), any());
        }

        @Test
        void replay_liveOperationAlreadyCompleted_marksPaidOut() {
            PawapayOperationEntity done = feePayout(PawapayOperationStatus.COMPLETED, "17323");
            when(operations.findLiveForCancellationFee(feeId)).thenReturn(Optional.of(done));

            assertThat(service.payOut(feeId)).isTrue();

            verify(port).markPaidOut(feeId, done.getId().toString());
            verify(alerts).resolveOpen(alertType);
            verify(submission, never()).submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void senderRefundStillInFlight_waits_withoutAlertNorTransition() {
            when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND))
                    .thenReturn(Optional.of(paymentOperation(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED,
                            "45922")));

            assertThat(service.payOut(feeId)).isFalse();

            verifyNoInteractions(submission);
            verify(port, never()).markPayoutPending(any(), any());
            verify(port, never()).markPaidOut(any(), any());
            verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
        }

        @Test
        void senderRefundFailed_staysPending() {
            when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND))
                    .thenReturn(Optional.of(paymentOperation(PawapayOperationKind.REFUND, PawapayOperationStatus.FAILED,
                            "45922")));

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "SENDER_REFUND_FAILED");
            verify(alerts).raiseOnce(eq(alertType), anyString(), anyMap());
            verifyNoInteractions(submission);
        }

        @Test
        void senderRefundOfAnotherAmount_staysPending() {
            // Remboursement entier relancé à la main : l'expéditeur a tout récupéré, le voyageur ne doit rien recevoir.
            when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND))
                    .thenReturn(Optional.of(paymentOperation(PawapayOperationKind.REFUND, PawapayOperationStatus.COMPLETED,
                            "65600")));

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "SENDER_REFUND_AMOUNT_MISMATCH");
            verifyNoInteractions(submission);
        }

        @Test
        void noSenderRefund_staysPending() {
            when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.empty());

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "SENDER_REFUND_MISSING");
        }

        @Test
        void heldTraveler_staysPending() {
            when(holdPolicy.statusOf(travelerId)).thenReturn(
                    new PayoutHoldStatus(LocalDateTime.now(), List.of(PayoutHoldReason.BANNED)));

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "PAYOUT_HELD_BANNED");
            verifyNoInteractions(submission);
        }

        @Test
        void pawapayRejection_staysPending_withOneAlert() {
            when(submission.submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer(call -> {
                        PawapayOperationEntity rejected = feePayout(PawapayOperationStatus.SUBMIT_REJECTED, "17323");
                        rejected.setFailureCode("PAYEE_LIMIT_REACHED");
                        return rejected;
                    });

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "PAWAPAY_REJECTED_PAYEE_LIMIT_REACHED");
            verify(alerts).raiseOnce(eq(alertType), anyString(), anyMap());
            verifyNoInteractions(adminAlert);
        }

        @Test
        void networkError_afterTheReservation_operationLeftToThePoller() {
            PawapayOperationEntity created = feePayout(PawapayOperationStatus.CREATED, "17323");
            when(submission.submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(PawapayErrors.providerUnavailable());
            when(operations.findLiveForCancellationFee(feeId)).thenReturn(Optional.empty(), Optional.of(created));

            assertThat(service.payOut(feeId)).isFalse();

            // L'opération réservée reste au poller : ni nouvelle soumission, ni passage en attente.
            verify(submission).submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any());
            verify(port, never()).markPayoutPending(any(), any());
        }

        @Test
        void networkError_beforeAnyReservation_staysPending() {
            when(submission.submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("base indisponible"));

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(eq(feeId), argThat(r -> r.startsWith("PAWAPAY_ERROR")));
        }

        @Test
        void threeFailedPayouts_stopTheAutomaticRetries_withADistinctAlert() {
            when(operations.countFailedForCancellationFee(feeId, null)).thenReturn(3L);

            assertThat(service.payOut(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "TOO_MANY_FAILED_PAYOUTS");
            verify(submission, never()).submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any());
            // Alerte à part : celle du versement en attente dit « nouvel essai chaque heure », ici il n'y en a plus.
            verify(alerts).raiseOnce(eq(CancellationFeePayoutService.STOPPED_ALERT_PREFIX + feeId),
                    contains("retry-payout"), anyMap());
        }

        @Test
        void belowTheCap_aNewPayoutIsSubmitted_onlyFailedOperationsAreCounted() {
            // Le plafond ne lit que les versements FAILED (refus à la soumission exclus, prouvé en base par l'IT).
            when(operations.countFailedForCancellationFee(feeId, null)).thenReturn(2L);

            assertThat(service.payOut(feeId)).isFalse();

            verify(operations).countFailedForCancellationFee(feeId, null);
            verify(submission).submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any());
            verify(alerts, never()).raiseOnce(eq(CancellationFeePayoutService.STOPPED_ALERT_PREFIX + feeId),
                    anyString(), anyMap());
        }

        @Test
        void rearmedByAnAdmin_onlyLaterFailuresCount() {
            Instant rearmed = Instant.parse("2026-11-21T10:00:00Z");
            when(port.findPayable(feeId)).thenReturn(Optional.of(new PayableCancellationFee(feeId, bidId, travelerId,
                    new BigDecimal("17323"), "XOF", rearmed)));
            when(operations.countFailedForCancellationFee(feeId, null)).thenReturn(5L);
            when(operations.countFailedForCancellationFee(feeId, rearmed)).thenReturn(0L);

            assertThat(service.payOut(feeId)).isFalse();

            verify(submission).submitCancellationFeePayout(any(), any(), any(), any(), any(), any(), any(), any());
            verify(port, never()).markPayoutPending(any(), any());
        }

        @Test
        void resumeAfterAdminRetry_closesTheStopAlert_thenAttemptsThroughTheSamePath() {
            assertThat(service.resumeAfterAdminRetry(feeId)).isFalse();

            verify(alerts).resolveOpen(CancellationFeePayoutService.STOPPED_ALERT_PREFIX + feeId);
            verify(submission).submitCancellationFeePayout(eq(feeId), eq(travelerId), eq(ACCOUNT_MSISDN),
                    eq("ORANGE_SEN"), eq("SN"), argThat(a -> same(a, "17323")), eq("XOF"), eq("fee-" + feeId));
        }

        @Test
        void resumeAfterAdminRetry_neverBypassesTheSenderRefundGate() {
            when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND))
                    .thenReturn(Optional.of(paymentOperation(PawapayOperationKind.REFUND, PawapayOperationStatus.COMPLETED,
                            "65600")));

            assertThat(service.resumeAfterAdminRetry(feeId)).isFalse();

            verify(port).markPayoutPending(feeId, "SENDER_REFUND_AMOUNT_MISMATCH");
            verifyNoInteractions(submission);
        }

        @Test
        void paidOut_closesTheStopAlertToo() {
            PawapayOperationEntity done = feePayout(PawapayOperationStatus.COMPLETED, "17323");
            when(operations.findLiveForCancellationFee(feeId)).thenReturn(Optional.of(done));

            assertThat(service.payOut(feeId)).isTrue();

            verify(alerts).resolveOpen(alertType);
            verify(alerts).resolveOpen(CancellationFeePayoutService.STOPPED_ALERT_PREFIX + feeId);
        }

        @Test
        void stopAlertType_fitsTheAdminAlertsColumn() {
            assertThat((CancellationFeePayoutService.STOPPED_ALERT_PREFIX + UUID.randomUUID()).length())
                    .isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
        }

        @Test
        void netZero_paidOutWithoutPawapay() {
            when(port.findPayable(feeId)).thenReturn(Optional.of(fee("0")));

            assertThat(service.payOut(feeId)).isTrue();

            verify(port).markPaidOut(feeId, null);
            verifyNoInteractions(submission, operations);
        }

        @Test
        void outcomeCompleted_marksPaidOut_andClosesTheAlert() {
            UUID operationId = UUID.randomUUID();

            service.mobileMoneyPayoutCompleted(feeId, operationId);

            verify(port).markPaidOut(feeId, operationId.toString());
            verify(alerts).resolveOpen(alertType);
        }

        @Test
        void outcomeCompleted_rowAlreadyPaid_noop() {
            when(port.findPayable(feeId)).thenReturn(Optional.empty());

            service.mobileMoneyPayoutCompleted(feeId, UUID.randomUUID());

            verify(port, never()).markPaidOut(any(), any());
        }

        @Test
        void outcomeFailed_staysPending_withOneAlert() {
            service.mobileMoneyPayoutFailed(feeId, "RECIPIENT_NOT_FOUND");

            verify(port).markPayoutPending(feeId, "PAWAPAY_PAYOUT_FAILED_RECIPIENT_NOT_FOUND");
            verify(alerts).raiseOnce(eq(alertType), anyString(), anyMap());
            verify(port, never()).markPaidOut(any(), any());
        }

        @Test
        void outcomeFailed_rowAlreadyPaid_noAlert() {
            when(port.findPayable(feeId)).thenReturn(Optional.empty());

            service.mobileMoneyPayoutFailed(feeId, "RECIPIENT_NOT_FOUND");

            verify(port, never()).markPayoutPending(any(), any());
            verify(alerts, never()).raiseOnce(anyString(), anyString(), anyMap());
        }

        private PayableCancellationFee fee(String net) {
            return new PayableCancellationFee(feeId, bidId, travelerId, new BigDecimal(net), "XOF", null);
        }

        private PawapayOperationEntity paymentOperation(PawapayOperationKind kind, PawapayOperationStatus status,
                                                        String amount) {
            PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), kind, payment.getId(), null,
                    new BigDecimal(amount), "XOF", "ORANGE_SEN", "SN", "221771234567");
            op.setStatus(status);
            return op;
        }

        private PawapayOperationEntity feePayout(PawapayOperationStatus status, String amount) {
            PawapayOperationEntity op = PawapayOperationEntity.forCancellationFee(UUID.randomUUID(), travelerId, feeId,
                    new BigDecimal(amount), "XOF", "ORANGE_SEN", "SN", ACCOUNT_MSISDN);
            op.setStatus(status);
            return op;
        }

        private MobileMoneyPayoutAccountEntity account(String provider, String country, String currency) {
            MobileMoneyPayoutAccountEntity a = new MobileMoneyPayoutAccountEntity(travelerId, provider, country, currency,
                    ACCOUNT_MSISDN, "masked", Instant.parse("2026-03-04T10:11:12Z"));
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            return a;
        }

        private void givenAccounts(MobileMoneyPayoutAccountEntity... live) {
            when(accountRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtAsc(travelerId))
                    .thenReturn(List.of(live));
        }
    }
}
