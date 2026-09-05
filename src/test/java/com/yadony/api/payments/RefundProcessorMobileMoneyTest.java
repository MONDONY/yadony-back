package com.yadony.api.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Rail PAWAPAY de {@link RefundProcessor} (tâche 17).
 *
 * <p><b>Ronde 1, point 10</b> : le constructeur est passé à 7 paramètres (dépendances mobile
 * money + {@code AdminAlertRepository} + {@code PlatformTransactionManager}, injectées par
 * constructeur — plus par champ). {@link RefundProcessorTest} (chemin Stripe) est mis à jour en
 * conséquence, mécaniquement (mêmes 3 premiers mocks, 4 mocks supplémentaires jamais exercés par
 * ses tests).
 *
 * <p><b>Écart déclaré par rapport au cahier des charges (piège 1 du brief)</b> : les deux tests
 * qui aboutissent à un refund posé ({@code escrow_claimsOnce_...} et
 * {@code escrow_liveRefundAlreadyExists_...}) assertent {@code payment.getPawapayRefundId()}
 * à {@code null} — PAS à l'id du refund. Le brief donne {@code isEqualTo(refund.getId())}, ce
 * qui correspondrait à {@code payment.setPawapayRefundId(refund.getId())} sur l'entité gérée
 * juste après le claim bulk {@code markRefundedIfEscrow} : exactement le défaut critique de la
 * tâche 16 (voir {@code PaymentRepository#attachPayoutId}), qui écraserait silencieusement
 * {@code REFUNDED} en base au prochain flush de l'entité. Le refund id est posé par l'UPDATE
 * ciblé {@code PaymentRepository#attachRefundId} : l'entité en mémoire ne le voit jamais, d'où
 * {@code isNull()} — et {@code verify(paymentRepository).attachRefundId(...)} prouve
 * positivement que la bonne méthode a été appelée.
 */
@ExtendWith(MockitoExtension.class)
class RefundProcessorMobileMoneyTest {

    @Mock PaymentRepository paymentRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlert;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock AdminAlertRepository alertRepository;
    @Mock PlatformTransactionManager transactionManager;

    private RefundProcessor processor;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        processor = new RefundProcessor(paymentRepository, auditService, adminAlert,
                operations, submission, alertRepository, transactionManager);
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
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(operations.findLive(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.empty());
        // Ronde 1, point 7 : le montant soumis est celui du DEPOSIT (deposit.getAmount()),
        // jamais payment.getAmount() — égaux dans ce fixture, distingués par le stub exact.
        when(submission.submitRefund(payment.getId(), deposit, deposit.getAmount())).thenReturn(refund);

        boolean done = processor.processRefund(payment.getId(), "PAYMENT_REFUNDED_BID_REJECTED", null, Map.of("reason", "bid_rejected"));

        assertThat(done).isTrue();
        // Piège 1 (voir Javadoc de la classe) : jamais de setter sur l'entité gérée après le
        // claim bulk — attachRefundId est le seul chemin qui pose réellement la colonne.
        assertThat(payment.getPawapayRefundId()).isNull();
        verify(paymentRepository).attachRefundId(payment.getId(), refund.getId());
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYMENT_REFUNDED_BID_REJECTED"), any(), any());
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
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(operations.findLive(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.of(live));

        assertThat(processor.processRefund(payment.getId(), "X", null, Map.of())).isTrue();
        assertThat(payment.getPawapayRefundId()).isNull();
        verify(paymentRepository).attachRefundId(payment.getId(), live.getId());
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
        verify(adminAlert).raise(eq(RefundProcessor.REJECTED_ALERT_PREFIX + payment.getId()), any(), any());
        verify(alertRepository).save(any(AdminAlertEntity.class));
        verify(paymentRepository, never()).attachRefundId(any(), any());
    }

    @Test
    void escrow_rejectedRefund_dedupedWhenAlreadyAlertedAndUnresolved() {
        payment.setStatus(PaymentStatus.ESCROW);
        PawapayOperationEntity deposit = op(PawapayOperationKind.DEPOSIT, PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity rejected = op(PawapayOperationKind.REFUND, PawapayOperationStatus.SUBMIT_REJECTED);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(operations.findLive(any(), any())).thenReturn(Optional.empty());
        when(submission.submitRefund(any(), any(), any())).thenReturn(rejected);
        when(alertRepository.findByTypeAndResolved(RefundProcessor.REJECTED_ALERT_PREFIX + payment.getId(), false))
                .thenReturn(List.of(new AdminAlertEntity()));

        assertThatThrownBy(() -> processor.processRefund(payment.getId(), "X", null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        verify(adminAlert, never()).raise(any(), any(), any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void escrow_withoutCompletedDeposit_alertsAndThrows() {
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.processRefund(payment.getId(), "X", null, Map.of()))
                .isInstanceOf(IllegalStateException.class);
        verify(adminAlert).raise(eq(RefundProcessor.NO_DEPOSIT_ALERT_PREFIX + payment.getId()), any(), any());
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
        verify(adminAlert).raise(eq(RefundProcessor.NO_DEPOSIT_ALERT_PREFIX + payment.getId()), any(), any());
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
}
