package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.pawapay.PawapayAmounts;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.requests.NegotiationMobileMoneyPort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rail mobile money sur un fil de négociation de colis, keyé {@code payments.negotiation_thread_id}
 * (bid_id NULL, comme la carte). Jumeau de {@link MobileMoneyBidPaymentService} pour l'argent ;
 * l'état du fil, lui, reste dans {@code requests/} ({@code NegotiationService}), qui appelle ce
 * service par {@link NegotiationMobileMoneyPort} et reçoit les issues par événements.
 *
 * <p>Même frontière transactionnelle critique que côté bid : {@link #createPendingPayment} ne
 * fait que créer la ligne, dans la transaction de l'appelant ; {@code initiateDeposit} (appel
 * HTTP séparé, transaction séparée) relit un paiement déjà commité avant d'appeler pawaPay.
 */
@Service
public class MobileMoneyNegotiationPaymentService {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyNegotiationPaymentService.class);
    static final String AUDIT_CREATED = "NEGOTIATION_DEPOSIT_PAYMENT_CREATED";
    static final String AUDIT_INITIATED = "NEGOTIATION_DEPOSIT_INITIATED";
    static final String AUDIT_CONFIRMED = "NEGOTIATION_DEPOSIT_CONFIRMED";
    static final String AUDIT_FAILED = "NEGOTIATION_DEPOSIT_FAILED";
    static final String AUDIT_CANCELLED = "NEGOTIATION_DEPOSIT_CANCELLED";
    static final String AUDIT_REFUNDED = "NEGOTIATION_DEPOSIT_REFUNDED";

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final PawapayProviderResolver providers;
    private final FirebaseContactService firebaseContact;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final PawapayProperties props;
    private final TransactionTemplate independentAuditTransaction;

    /** Même motif que {@link MobileMoneyBidPaymentService#adminAlert} : optionnel, jamais dans le constructeur. */
    @Autowired(required = false)
    private AdminAlertService adminAlert;

    public MobileMoneyNegotiationPaymentService(PaymentRepository paymentRepository, UserRepository userRepository,
                                                PawapayOperationService operations, PawapaySubmissionService submission,
                                                PawapayProviderResolver providers, FirebaseContactService firebaseContact,
                                                AuditService audit, ApplicationEventPublisher events,
                                                PlatformTransactionManager transactionManager, PawapayProperties props) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.operations = operations;
        this.submission = submission;
        this.providers = providers;
        this.firebaseContact = firebaseContact;
        this.audit = audit;
        this.events = events;
        this.props = props;
        this.independentAuditTransaction = new TransactionTemplate(transactionManager);
        this.independentAuditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── Création (transaction de l'appelant) ─────────────────────────────

    @Transactional
    public NegotiationMobileMoneyPort.PendingDeposit createPendingPayment(UUID threadId, UUID senderId, UUID travelerId,
                                                                          BigDecimal net, BigDecimal commissionRate,
                                                                          String currency) {
        // Prix du fil, jamais la grille bid : le net négocié et le taux (promo compris) sont figés
        // sur le fil ; ici on ne fait qu'arrondir à l'unité mineure de la devise (0 décimale en XOF).
        BigDecimal roundedNet = PawapayAmounts.round(net, currency);
        BigDecimal commission = PawapayAmounts.round(roundedNet.multiply(commissionRate), currency);
        BigDecimal gross = roundedNet.add(commission);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(props.depositDeadlineMinutes());

        Optional<PaymentEntity> existing = paymentRepository.findByNegotiationThreadId(threadId);
        if (existing.isPresent()) {
            PaymentEntity p = existing.get();
            if (p.getRail() == PaymentRail.STRIPE && p.getStatus() != PaymentStatus.CANCELLED) {
                // Un hold carte vivant sur ce fil : l'expéditeur doit d'abord basculer (le
                // checkout annule le hold), jamais deux séquestres pour un même colis.
                throw new YadonyBusinessException(HttpStatus.CONFLICT, "negotiation-card-escrow-in-flight",
                        "Card Escrow In Flight", "Un paiement par carte est déjà en cours sur cette négociation.");
            }
            if (p.getStatus() == PaymentStatus.PENDING && p.getRail() == PaymentRail.PAWAPAY) {
                return new NegotiationMobileMoneyPort.PendingDeposit(p.getId(), p.getAmount(), p.getCommissionAmount(), expiresAt);
            }
            if (p.getStatus() != PaymentStatus.CANCELLED) {
                throw new YadonyBusinessException(HttpStatus.CONFLICT, "negotiation-deposit-already-settled",
                        "Deposit Already Settled", "Ce paiement n'est plus en attente (" + p.getStatus() + ").");
            }
            // CANCELLED (échéance passée, bascule carte → mobile money) : recyclé, UNIQUE(negotiation_thread_id) oblige.
            p.setRail(PaymentRail.PAWAPAY);
            p.setStripePaymentIntentId(null);
            p.setAmount(gross);
            p.setCommissionAmount(commission);
            p.setCurrency(currency);
            p.setStatus(PaymentStatus.PENDING);
            p.setLegacyDestinationCharge(false);
            p = paymentRepository.save(p);
            audit.log("PAYMENT", p.getId(), AUDIT_CREATED, senderId, Map.of("threadId", threadId.toString(),
                    "amount", gross.toPlainString(), "commission", commission.toPlainString(), "currency", currency, "recycled", "true"));
            return new NegotiationMobileMoneyPort.PendingDeposit(p.getId(), gross, commission, expiresAt);
        }

        PaymentEntity payment = new PaymentEntity();
        payment.setNegotiationThreadId(threadId);
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStripePaymentIntentId(null);
        payment.setAmount(gross);
        payment.setCommissionAmount(commission);
        payment.setCurrency(currency);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setLegacyDestinationCharge(false);
        payment = paymentRepository.save(payment);
        audit.log("PAYMENT", payment.getId(), AUDIT_CREATED, senderId, Map.of("threadId", threadId.toString(),
                "travelerId", travelerId.toString(), "amount", gross.toPlainString(),
                "commission", commission.toPlainString(), "currency", currency));
        return new NegotiationMobileMoneyPort.PendingDeposit(payment.getId(), gross, commission, expiresAt);
    }

    // ── Libération (échéance, renoncement de l'expéditeur) ───────────────

    @Transactional
    public NegotiationMobileMoneyPort.ReleaseOutcome releasePendingDeposit(UUID threadId) {
        Optional<PaymentEntity> payment = paymentRepository.findByNegotiationThreadIdForUpdate(threadId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY);
        if (payment.isEmpty() || payment.get().getStatus() != PaymentStatus.PENDING) {
            return NegotiationMobileMoneyPort.ReleaseOutcome.NOTHING_PENDING;
        }
        PaymentEntity p = payment.get();
        Optional<PawapayOperationEntity> deposit = operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT);
        if (deposit.map(o -> !o.getStatus().isFinal()).orElse(false)) {
            return NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_OPEN;
        }
        if (deposit.map(o -> o.getStatus() == PawapayOperationStatus.COMPLETED).orElse(false)) {
            log.error("Fil {} : deposit COMPLETED mais paiement {} encore PENDING, libération abandonnée", threadId, p.getId());
            return NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_COMPLETED_NOT_APPLIED;
        }
        if (paymentRepository.markCancelledIfPending(p.getId()) == 0) {
            return NegotiationMobileMoneyPort.ReleaseOutcome.NOTHING_PENDING;
        }
        audit.log("PAYMENT", p.getId(), AUDIT_CANCELLED, null, Map.of("threadId", threadId.toString()));
        return NegotiationMobileMoneyPort.ReleaseOutcome.CANCELLED;
    }

    // ── Remboursement d'un séquestre orphelin ────────────────────────────

    @Transactional
    public boolean refundEscrowedDeposit(UUID threadId) {
        Optional<PaymentEntity> payment = paymentRepository.findByNegotiationThreadIdForUpdate(threadId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY);
        if (payment.isEmpty()) return false;
        PaymentEntity p = payment.get();
        if (paymentRepository.markRefundedIfEscrow(p.getId()) == 0) return false;
        PawapayOperationEntity deposit = operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT)
                .filter(o -> o.getStatus() == PawapayOperationStatus.COMPLETED)
                .orElseThrow(() -> new IllegalStateException("Séquestre sans deposit COMPLETED : paiement " + p.getId()));
        submitRefund(p, deposit);
        return true;
    }

    /** Montant soumis = celui du DEPOSIT, jamais payment.getAmount() (même règle que RefundProcessor). */
    private void submitRefund(PaymentEntity payment, PawapayOperationEntity deposit) {
        PawapayOperationEntity refund = submission.submitRefund(payment.getId(), deposit, deposit.getAmount());
        if (refund.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            if (adminAlert != null) {
                adminAlert.raise("PAWAPAY_NEGOTIATION_REFUND_REJECTED",
                        "pawaPay a refusé le remboursement du dépôt d'une négociation (payment " + payment.getId() + ") : " + refund.getFailureCode(),
                        Map.of("paymentId", payment.getId().toString(), "refundOperationId", refund.getId().toString()));
            }
            throw new IllegalStateException("pawaPay refund rejected: " + refund.getFailureCode());
        }
        independentAuditTransaction.executeWithoutResult(s -> audit.log("PAYMENT", payment.getId(), AUDIT_REFUNDED, null,
                Map.of("depositOperationId", deposit.getId().toString(), "refundOperationId", refund.getId().toString(),
                        "refundStatus", refund.getStatus().name())));
        if (adminAlert != null) {
            adminAlert.raise("PAWAPAY_NEGOTIATION_DEPOSIT_ORPHANED",
                    "Dépôt mobile money encaissé sur une négociation qui n'est plus scellable, remboursement "
                            + refund.getStatus() + " (payment " + payment.getId() + ")",
                    Map.of("paymentId", payment.getId().toString(), "refundOperationId", refund.getId().toString()));
        }
    }

    static YadonyBusinessException notFound(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND, code, "Not Found", detail);
    }

    static YadonyBusinessException payerUnsupported(String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payer-unsupported",
                "Mobile Money Payer Unsupported", detail);
    }
}
