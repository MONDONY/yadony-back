package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.voucher.CommissionVoucherService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Fabrique de test du versement « réserver → appeler → finaliser » (PROD_CHECKLIST 9.23) : en
 * test unitaire, réservations simulées (chaque transition réussit) et transactions sans effet ;
 * en test d'intégration, vrai repository et vrai gestionnaire de transactions.
 */
public final class ReleaseTestSupport {

    private ReleaseTestSupport() {}

    /** Repository simulé : aucune réservation existante, chaque transition conditionnelle réussit. */
    public static StripeReleaseIntentRepository intentRepository() {
        StripeReleaseIntentRepository repository = mock(StripeReleaseIntentRepository.class);
        lenient().when(repository.findByPaymentId(any())).thenReturn(Optional.empty());
        lenient().when(repository.save(any(StripeReleaseIntentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.markDone(any(), any())).thenReturn(1);
        lenient().when(repository.markReverted(any(), any())).thenReturn(1);
        lenient().when(repository.recordRetry(any(), any(), any())).thenReturn(1);
        lenient().when(repository.claimLease(any(), any(), any())).thenReturn(1);
        return repository;
    }

    public static StripeReleaseExecutor executor(PaymentRepository paymentRepository, StripeTransferLookup lookup,
                                                 AuditService auditService, ApplicationEventPublisher eventPublisher,
                                                 AdminAlertEscalator alertEscalator) {
        return executor(intentRepository(), paymentRepository, lookup, auditService, eventPublisher, alertEscalator);
    }

    public static StripeReleaseExecutor executor(StripeReleaseIntentRepository intents, PaymentRepository paymentRepository,
                                                 StripeTransferLookup lookup, AuditService auditService,
                                                 ApplicationEventPublisher eventPublisher,
                                                 AdminAlertEscalator alertEscalator) {
        return new StripeReleaseExecutor(intents, paymentRepository, lookup, auditService, eventPublisher,
                alertEscalator != null ? alertEscalator : mock(AdminAlertEscalator.class),
                TransactionOperations.withoutTransaction(), Clock.systemUTC(), StripeReleaseExecutor.NO_GUARD);
    }

    /** Vrai exécuteur (base et transactions réelles), pour les tests d'intégration. */
    public static StripeReleaseExecutor executor(StripeReleaseIntentRepository intents, PaymentRepository paymentRepository,
                                                 StripeTransferLookup lookup, AuditService auditService,
                                                 ApplicationEventPublisher eventPublisher,
                                                 AdminAlertEscalator alertEscalator,
                                                 PlatformTransactionManager transactionManager,
                                                 StripeReleaseGuard guard) {
        return new StripeReleaseExecutor(intents, paymentRepository, lookup, auditService, eventPublisher,
                alertEscalator, transactionManager, guard);
    }

    /** Ancienne signature du listener, pour les tests unitaires (réservations simulées). */
    public static DeliveryEventListener listener(PaymentRepository paymentRepository, UserRepository userRepository,
                                                 AuditService auditService, ApplicationEventPublisher eventPublisher,
                                                 BidRepository bidRepository, AdminAlertService adminAlert,
                                                 CommissionVoucherService voucherService,
                                                 MobileMoneyPayoutInitiator payoutInitiator, PayoutHoldPolicy holdPolicy,
                                                 AdminAlertEscalator alertEscalator, EscrowCaptureService escrowCapture,
                                                 StripeTransferLookup lookup, DisputeRepository disputeRepository) {
        return new DeliveryEventListener(paymentRepository, userRepository, auditService, bidRepository, adminAlert,
                voucherService, payoutInitiator, holdPolicy, alertEscalator, escrowCapture,
                executor(paymentRepository, lookup, auditService, eventPublisher, alertEscalator),
                disputeRepository, TransactionOperations.withoutTransaction());
    }

    /** Listener sur base et transactions réelles (tests d'intégration). */
    public static DeliveryEventListener listener(PaymentRepository paymentRepository, UserRepository userRepository,
                                                 AuditService auditService, ApplicationEventPublisher eventPublisher,
                                                 BidRepository bidRepository, AdminAlertService adminAlert,
                                                 CommissionVoucherService voucherService,
                                                 MobileMoneyPayoutInitiator payoutInitiator, PayoutHoldPolicy holdPolicy,
                                                 AdminAlertEscalator alertEscalator, EscrowCaptureService escrowCapture,
                                                 StripeTransferLookup lookup, DisputeRepository disputeRepository,
                                                 StripeReleaseIntentRepository intents,
                                                 PlatformTransactionManager transactionManager,
                                                 StripeReleaseGuard guard) {
        return new DeliveryEventListener(paymentRepository, userRepository, auditService, bidRepository, adminAlert,
                voucherService, payoutInitiator, holdPolicy, alertEscalator, escrowCapture,
                executor(intents, paymentRepository, lookup, auditService, eventPublisher, alertEscalator,
                        transactionManager, guard),
                disputeRepository, EscrowCaptureService.requiresNew(transactionManager));
    }
}
