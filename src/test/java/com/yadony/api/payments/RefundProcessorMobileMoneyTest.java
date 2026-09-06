package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Rail PAWAPAY de {@link RefundProcessor}. Les alertes de ce rail passent par
 * {@code AdminAlertEscalator} (mocké ici, dédup testée à part) : on vérifie seulement le type
 * levé. Aucun test n'asserte de colonne {@code payments.pawapay_*} : le lien paiement →
 * opération vit dans {@code pawapay_operations} et {@code RefundProcessor} ne mute jamais
 * l'entité {@code PaymentEntity} après son claim bulk.
 */
@ExtendWith(MockitoExtension.class)
class RefundProcessorMobileMoneyTest {

    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlert;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock AdminAlertEscalator alerts;
    @Mock PlatformTransactionManager transactionManager;

    private RefundProcessor processor;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        processor = new RefundProcessor(paymentRepository, auditService, adminAlert,
                operations, submission, alerts, transactionManager);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(UUID.randomUUID());
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setAmount(new BigDecimal("16800"));
        payment.setCommissionAmount(new BigDecimal("1800"));
        payment.setCurrency("XOF");
        // lenient : les deux tests de longueur de préfixe (noDepositAlertType_.../rejectedAlertType_...)
        // n'appellent jamais processRefund et n'exercent donc jamais ce stub — sans lenient,
        // MockitoExtension (STRICT_STUBS par défaut) les ferait échouer en UnnecessaryStubbingException.
        org.mockito.Mockito.lenient().when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
    }

    private PawapayOperationEntity op(PawapayOperationKind kind, PawapayOperationStatus status) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), kind, payment.getId(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        return o;
    }

    @Test
    void pending_isCancelled_withoutAnyPawapayCall() {
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(1);

        boolean done = processor.processRefund(payment.getId(), "PAYMENT_REFUNDED", null, Map.of("reason", "trip_cancelled"));

        assertThat(done).isTrue();
        verify(submission, never()).submitRefund(any(), any(), any());
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYMENT_REFUNDED"), any(), any());
    }

    @Test
    void escrow_claimsOnce_thenSubmitsRefundOfTheCompletedDeposit() {
        payment.setStatus(PaymentStatus.ESCROW);
        PawapayOperationEntity deposit = op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity refund = op(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        // Revue finale, point 2 : la garde en tête de refundEscrowedMobileMoney interroge
        // maintenant aussi findLive(..., PAYOUT), avant le claim — aucun versement ici.
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(operations.findLive(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.empty());
        // Ronde 1, point 7 : le montant soumis est celui du DEPOSIT (deposit.getAmount()),
        // jamais payment.getAmount() — égaux dans ce fixture, distingués par le stub exact.
        when(submission.submitRefund(payment.getId(), deposit, deposit.getAmount())).thenReturn(refund);

        boolean done = processor.processRefund(payment.getId(), "PAYMENT_REFUNDED_BID_REJECTED", null, Map.of("reason", "bid_rejected"));

        assertThat(done).isTrue();
        // Piège 1 (voir Javadoc de la classe) : jamais de setter sur l'entité gérée après le
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYMENT_REFUNDED_BID_REJECTED"), any(), any());
    }

    /**
     * Revue finale, point 2 (CRITIQUE) : {@code refundEscrowedMobileMoney} ne consultait jamais
     * les opérations PAYOUT — sa seule protection était {@code payment.status == ESCROW}. Or la
     * branche crée elle-même l'état « paiement ESCROW alors qu'un versement est parti »
     * ({@code MobileMoneyPayoutInitiator} : soumission acceptée, timeout HTTP, rollback du
     * claim ; le poller mène ensuite l'opération à COMPLETED pendant que le paiement redevient
     * ESCROW). Sans cette garde, un opérateur pouvait rembourser le brut à l'expéditeur pendant
     * que le net était déjà chez le voyageur — perte sèche, sans alerte. {@code findLive} couvre
     * aussi COMPLETED (LIVE_OR_DONE) : LE TEST DEMANDÉ PAR LA REVUE FINALE, point 2.
     */
    @Test
    void escrow_payoutAlreadyLiveOrCompleted_alertsAndThrows_withoutClaimingOrRefunding() {
        payment.setStatus(PaymentStatus.ESCROW);
        PawapayOperationEntity payout = op(PawapayOperationKind.PAYOUT, PawapayOperationStatus.COMPLETED);
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(payout));

        assertThatThrownBy(() -> processor.processRefund(payment.getId(), "X", null, Map.of()))
                .isInstanceOf(IllegalStateException.class);

        verify(alerts).raiseOnce(eq(RefundProcessor.PAYOUT_EXISTS_ALERT_PREFIX + payment.getId()), any(), any());
        // La garde agit AVANT le claim : jamais de tentative de remboursement, même annulée.
        verify(paymentRepository, never()).markRefundedIfEscrow(any());
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    /** Ronde 1, point 5 (motif repris) : préfixe ≤ 24 caractères, préfixe + UUID ≤ 60. */
    @Test
    void payoutExistsAlertType_fitsInAdminAlertsTypeColumn() {
        assertThat(RefundProcessor.PAYOUT_EXISTS_ALERT_PREFIX.length()).isLessThanOrEqualTo(24);
        assertThat((RefundProcessor.PAYOUT_EXISTS_ALERT_PREFIX + UUID.randomUUID()).length()).isLessThanOrEqualTo(60);
    }

    @Test
    void escrow_secondCall_isNoop() {
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(0);
        assertThat(processor.processRefund(payment.getId(), "X", null, Map.of())).isFalse();
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    @Test
    void escrow_liveRefundAlreadyExists_isRecovered_notResubmitted() {
        payment.setStatus(PaymentStatus.ESCROW);
        PawapayOperationEntity deposit = op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity live = op(PawapayOperationKind.REFUND, PawapayOperationStatus.PROCESSING);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        // Revue finale, point 2 : garde en tête, aucun versement ici.
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(operations.findLive(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.of(live));

        assertThat(processor.processRefund(payment.getId(), "X", null, Map.of())).isTrue();
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    @Test
    void escrow_rejectedRefund_alertsAndThrows_soTheClaimRollsBack() {
        payment.setStatus(PaymentStatus.ESCROW);
        PawapayOperationEntity deposit = op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity rejected = op(PawapayOperationKind.REFUND, PawapayOperationStatus.SUBMIT_REJECTED);
        rejected.setFailureCode("DEPOSIT_NOT_FOUND");
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(submission.submitRefund(any(), any(), any())).thenReturn(rejected);

        assertThatThrownBy(() -> processor.processRefund(payment.getId(), "X", null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        // Ronde 1, point 5 : type dédupliqué PAR PAIEMENT (préfixe + paymentId), plus le
        // constant bare du brief.
        verify(alerts).raiseOnce(eq(RefundProcessor.REJECTED_ALERT_PREFIX + payment.getId()), any(), any());
    }

    @Test
    void escrow_withoutCompletedDeposit_alertsAndThrows() {
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.processRefund(payment.getId(), "X", null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        verify(alerts).raiseOnce(eq(RefundProcessor.NO_DEPOSIT_ALERT_PREFIX + payment.getId()), any(), any());
    }

    /**
     * Ronde 1, point 6 : le brief ne testait que l'ABSENCE totale de deposit
     * ({@code Optional.empty()}) — le prédicat {@code .filter(d -> d.getStatus() == COMPLETED)}
     * n'était donc jamais exercé (on peut le supprimer sans faire rougir aucun test existant).
     * Ce test stub un deposit PRÉSENT mais {@code FAILED} : sans le filtre, le code
     * rembourserait contre un dépôt qui n'a jamais abouti.
     */
    @Test
    void escrow_depositExistsButFailed_alertsAndThrows() {
        payment.setStatus(PaymentStatus.ESCROW);
        PawapayOperationEntity failedDeposit = op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.FAILED);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(failedDeposit));

        assertThatThrownBy(() -> processor.processRefund(payment.getId(), "X", null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        verify(alerts).raiseOnce(eq(RefundProcessor.NO_DEPOSIT_ALERT_PREFIX + payment.getId()), any(), any());
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    @Test
    void released_isNeverRefunded() {
        payment.setStatus(PaymentStatus.RELEASED);
        assertThat(processor.processRefund(payment.getId(), "X", null, Map.of())).isFalse();
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    /** Ronde 1, point 5 : admin_alerts.type est VARCHAR(60) — préfixe + UUID (36) doit tenir. */
    @Test
    void noDepositAlertType_fitsInAdminAlertsTypeColumn() {
        assertThat((RefundProcessor.NO_DEPOSIT_ALERT_PREFIX + UUID.randomUUID()).length()).isLessThanOrEqualTo(60);
    }

    @Test
    void rejectedAlertType_fitsInAdminAlertsTypeColumn() {
        assertThat((RefundProcessor.REJECTED_ALERT_PREFIX + UUID.randomUUID()).length()).isLessThanOrEqualTo(60);
    }

    /**
     * L'audit du chemin ESCROW accepté part dans SA PROPRE transaction ({@code REQUIRES_NEW}) :
     * la trace d'un remboursement réellement soumis survit même si la transaction ambiante (le
     * claim) échouait ensuite — capturée via le {@code PlatformTransactionManager} mocké, comme
     * {@code MobileMoneyBidPaymentServiceEscrowTest} le fait pour {@code refundAfterCancel}.
     */
    @Test
    void escrow_claimsOnce_auditsInItsOwnIndependentTransaction() {
        payment.setStatus(PaymentStatus.ESCROW);
        PawapayOperationEntity deposit = op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity refund = op(PawapayOperationKind.REFUND, PawapayOperationStatus.ACCEPTED);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        // Revue finale, point 2 : garde en tête, aucun versement ici.
        when(operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.empty());
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(operations.findLive(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.empty());
        when(submission.submitRefund(payment.getId(), deposit, deposit.getAmount())).thenReturn(refund);

        boolean done = processor.processRefund(payment.getId(), "PAYMENT_REFUNDED_BID_REJECTED", null, Map.of());

        assertThat(done).isTrue();
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYMENT_REFUNDED_BID_REJECTED"), any(), any());

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
