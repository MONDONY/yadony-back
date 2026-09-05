package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.events.MobileMoneyDepositFailedEvent;
import com.yadony.api.payments.events.MobileMoneyPaymentConfirmedEvent;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * confirmEscrow / notifyDepositFailed — construit le service avec les mêmes mocks que
 * {@code MobileMoneyBidPaymentServiceTest}.
 *
 * <p>Écart déclaré par rapport au cahier des charges (voir task-14-report.md) :
 * {@code MobileMoneyBidPaymentService} a un constructeur à 16 paramètres depuis la tâche
 * 13 (ajout de {@code commissionRateResolver}, {@code promoService}, {@code voucherService},
 * {@code transactionManager}, {@code props} — voir {@code MobileMoneyBidPaymentServiceTest}).
 * L'appel à 12 arguments du cahier des charges ne compile pas contre le constructeur réel :
 * complété ici à 16 arguments (7 mocks utilisés par confirmEscrow/notifyDepositFailed +
 * 8 {@code null} pour les collaborateurs que ces deux méthodes ne touchent jamais, plus le
 * mock {@code transactionManager} — Ronde 1, point 3 : {@code refundAfterCancel} utilise
 * désormais {@code independentAuditTransaction}, construit en constructeur à partir de ce
 * gestionnaire ; un {@code null} littéral y ferait lever une NullPointerException dès le
 * premier appel à {@code executeWithoutResult}).
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyBidPaymentServiceEscrowTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock ApplicationEventPublisher events;
    @Mock AdminAlertService adminAlert;
    @Mock com.yadony.api.common.AuditService audit;
    @Mock PlatformTransactionManager transactionManager;

    private MobileMoneyBidPaymentService service;
    private PaymentEntity payment;
    private BidEntity bid;
    private AnnouncementEntity announcement;
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MobileMoneyBidPaymentService(bidRepository, announcementRepository, null, paymentRepository,
                operations, submission, null, null, null, audit, events, null, null, null, transactionManager, null);
        ReflectionTestUtils.setField(service, "adminAlert", adminAlert);
        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(travelerId);
        announcement.setCurrency("XOF");
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(bid.getId());
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(new BigDecimal("16800"));
        payment.setCommissionAmount(new BigDecimal("1800"));
        payment.setCurrency("XOF");
    }

    private PawapayOperationEntity deposit(PawapayOperationStatus status) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, payment.getId(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        return o;
    }

    @Test
    void confirmEscrow_movesPaymentToEscrow_finalizesBid_andPublishesConfirmed() {
        PawapayOperationEntity op = deposit(PawapayOperationStatus.COMPLETED);
        when(paymentRepository.markEscrowIfPending(eq(payment.getId()), eq(op.getId()), any())).thenReturn(1);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        // Ronde 1, point 5 : simple lecture, plus de verrou pessimiste sur l'annonce — rien
        // n'y est jamais écrit ici (la capacité a été réservée à l'acceptation).
        when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));

        service.confirmEscrow(op.getId(), payment.getId());

        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        assertThat(bid.getQrToken()).isNotNull();
        assertThat(bid.getTrackingToken()).isNotNull();
        assertThat(bid.getTrackingNumber()).startsWith("DON-").hasSize(12);
        assertThat(bid.getAwaitingPaymentExpiresAt()).isNull();
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(MobileMoneyPaymentConfirmedEvent.class)
                .satisfies(e -> {
                    MobileMoneyPaymentConfirmedEvent c = (MobileMoneyPaymentConfirmedEvent) e;
                    assertThat(c.senderId()).isEqualTo(bid.getSenderId());
                    assertThat(c.travelerId()).isEqualTo(travelerId);
                    assertThat(c.amount()).isEqualByComparingTo("16800");
                });
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("MM_ESCROW"), eq(bid.getSenderId()), any());
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    @Test
    void confirmEscrow_replay_isNoop() {
        PawapayOperationEntity op = deposit(PawapayOperationStatus.COMPLETED);
        payment.setStatus(PaymentStatus.ESCROW);
        when(paymentRepository.markEscrowIfPending(any(), any(), any())).thenReturn(0);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));

        service.confirmEscrow(op.getId(), payment.getId());

        verify(events, never()).publishEvent(any());
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    @Test
    void confirmEscrow_afterDeadlineCancellation_refundsAutomatically() {
        PawapayOperationEntity op = deposit(PawapayOperationStatus.COMPLETED);
        payment.setStatus(PaymentStatus.CANCELLED);
        when(paymentRepository.markEscrowIfPending(any(), any(), any())).thenReturn(0);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(operations.get(op.getId())).thenReturn(op);
        PawapayOperationEntity refund = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), op.getId(),
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        refund.setStatus(PawapayOperationStatus.ACCEPTED);
        when(submission.submitRefund(payment.getId(), op, new BigDecimal("16800"))).thenReturn(refund);

        service.confirmEscrow(op.getId(), payment.getId());

        verify(submission).submitRefund(payment.getId(), op, new BigDecimal("16800"));
        verify(adminAlert).raise(eq("PAWAPAY_DEPOSIT_AFTER_CANCEL"), any(), any());
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("MM_DEPOSIT_AFTER_CANCEL_REFUNDED"), any(), any());
        verify(events, never()).publishEvent(any());
    }

    /**
     * Ronde 1, point 3 (Important) : même défaut que celui déjà corrigé à la tâche 13 pour
     * MM_DEPOSIT_INITIATED. La soumission du remboursement a DÉJÀ commité sa ligne
     * d'opération ({@code REQUIRES_NEW}, dans {@code PawapayOperationService#create}) et
     * l'appel HTTP à pawaPay est déjà parti — irréversible — quand {@code refundAfterCancel}
     * écrit l'entrée d'audit. Si cette écriture rejoint la transaction AMBIANTE (celle du
     * listener) et qu'un throw plus loin dans le même appel l'annule, l'audit disparaît
     * alors que l'argent a réellement bougé. Preuve empiriquement discriminante (comme la
     * tâche 13) : un {@code TransactionTemplate} resté en propagation par défaut
     * ({@code REQUIRED}) produirait le même appel à {@code getTransaction}, donc
     * {@code verify(transactionManager).getTransaction(any())} seul ne prouverait rien —
     * la propagation EXACTE transmise est capturée et assertée.
     */
    @Test
    void confirmEscrow_afterDeadlineCancellation_auditUsesItsOwnIndependentTransaction() {
        PawapayOperationEntity op = deposit(PawapayOperationStatus.COMPLETED);
        payment.setStatus(PaymentStatus.CANCELLED);
        when(paymentRepository.markEscrowIfPending(any(), any(), any())).thenReturn(0);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(operations.get(op.getId())).thenReturn(op);
        PawapayOperationEntity refund = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), op.getId(),
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        refund.setStatus(PawapayOperationStatus.ACCEPTED);
        when(submission.submitRefund(payment.getId(), op, new BigDecimal("16800"))).thenReturn(refund);

        service.confirmEscrow(op.getId(), payment.getId());

        ArgumentCaptor<org.springframework.transaction.TransactionDefinition> definition =
                ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void confirmEscrow_bidNoLongerAwaiting_refundsAndMarksRefunded() {
        PawapayOperationEntity op = deposit(PawapayOperationStatus.COMPLETED);
        bid.setStatus(BidStatus.CANCELLED);
        when(paymentRepository.markEscrowIfPending(any(), any(), any())).thenReturn(1);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findByIdForUpdate(bid.getId())).thenReturn(Optional.of(bid));
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        when(operations.get(op.getId())).thenReturn(op);
        PawapayOperationEntity refund = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), op.getId(),
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        refund.setStatus(PawapayOperationStatus.ACCEPTED);
        when(submission.submitRefund(any(), any(), any())).thenReturn(refund);

        service.confirmEscrow(op.getId(), payment.getId());

        verify(paymentRepository).markRefundedIfEscrow(payment.getId());
        verify(submission).submitRefund(payment.getId(), op, new BigDecimal("16800"));
        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void notifyDepositFailed_publishesFailedEvent_andKeepsPaymentPending() {
        PawapayOperationEntity op = deposit(PawapayOperationStatus.FAILED);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));

        service.notifyDepositFailed(op.getId(), payment.getId(), "PAYMENT_NOT_APPROVED");

        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(MobileMoneyDepositFailedEvent.class)
                .extracting(e -> ((MobileMoneyDepositFailedEvent) e).failureCode()).isEqualTo("PAYMENT_NOT_APPROVED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(audit).log(eq("PAYMENT"), eq(payment.getId()), eq("MM_DEPOSIT_FAILED"), any(), any());
    }

    /**
     * Ronde 1, point 7 : la troncature à 64 caractères n'était affirmée par aucun test —
     * ajouté tel que demandé, un code d'échec de 100 caractères et l'assertion qu'il en
     * reste exactement 64 dans l'événement publié.
     */
    @Test
    void notifyDepositFailed_truncatesFailureCodeAt64Characters() {
        PawapayOperationEntity op = deposit(PawapayOperationStatus.FAILED);
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        String longCode = "X".repeat(100);

        service.notifyDepositFailed(op.getId(), payment.getId(), longCode);

        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(((MobileMoneyDepositFailedEvent) ev.getValue()).failureCode())
                .hasSize(64)
                .isEqualTo(longCode.substring(0, 64));
    }
}
