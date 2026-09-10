package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.Msisdn;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.events.MobileMoneyNegotiationDepositConfirmedEvent;
import com.yadony.api.payments.events.MobileMoneyNegotiationDepositFailedEvent;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyNegotiationStatusResponse;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse;
import com.yadony.api.payments.pawapay.PawapayAmounts;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.PawapayText;
import com.yadony.api.requests.NegotiationMobileMoneyPort;
import java.math.BigDecimal;
import java.time.Instant;
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

    // ── Initiation du deposit (transaction séparée de la création) ───────

    @Transactional
    public MobileMoneyNegotiationStatusResponse initiateDeposit(UUID threadId, UUID senderId, String phoneOverride,
                                                                LocalDateTime deadline) {
        PaymentEntity payment = paymentRepository.findByNegotiationThreadIdForUpdate(threadId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY)
                .orElseThrow(() -> notFound("mobile-money-payment-not-found", "Aucun paiement mobile money pour cette négociation"));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "mobile-money-payment-not-pending",
                    "Payment Not Pending", "Ce paiement n'est plus en attente (" + payment.getStatus() + ")");
        }
        if (deadline == null || deadline.isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-payment-expired",
                    "Payment Expired", "Le délai de paiement est dépassé.");
        }
        Optional<PawapayOperationEntity> live = operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT);
        if (live.isPresent()) {
            return status(threadId, deadline, Optional.of(payment), live);
        }
        if (!props.enabled()) {
            throw PawapayErrors.disabled();
        }
        String msisdn = resolvePayerMsisdn(senderId, phoneOverride);
        PawapayProviderResolver.Resolved resolved;
        try {
            resolved = providers.resolve(msisdn, PawapayOperationKind.DEPOSIT, payment.getCurrency(),
                    "l'initiation du deposit pour la négociation " + threadId);
        } catch (PawapayProviderResolver.UnsupportedNumberException e) {
            throw payerUnsupported(switch (e.reason()) {
                case NO_PROVIDER -> "Aucun opérateur mobile money reconnu pour ce numéro.";
                case OPERATION_CLOSED -> e.providerLabel() + " ne permet pas le paiement pour le moment.";
                case CURRENCY_MISMATCH -> "Ce numéro paie en " + e.providerCurrency() + ", ce colis est en " + payment.getCurrency() + ".";
                case COUNTRY_UNKNOWN -> "Pays non reconnu pour ce numéro.";
            });
        }
        var limits = resolved.config().deposit();
        if (limits.minAmount() != null && payment.getAmount().compareTo(limits.minAmount()) < 0
                || limits.maxAmount() != null && payment.getAmount().compareTo(limits.maxAmount()) > 0) {
            throw payerUnsupported("Montant hors des limites de " + resolved.providerLabel() + ".");
        }
        String successfulUrl = null;
        String failedUrl = null;
        if (resolved.config().isRedirectDeposit()) {
            String base = props.returnBaseUrl() + "/api/v1/pawapay/return/thread/" + threadId;
            successfulUrl = base + "?outcome=success";
            failedUrl = base + "?outcome=failed";
        }
        PawapayOperationEntity op = submission.submitDeposit(payment.getId(), resolved.msisdn(), resolved.provider(),
                resolved.countryAlpha2(), payment.getAmount(), payment.getCurrency(), "thread-" + threadId, successfulUrl, failedUrl);
        independentAuditTransaction.executeWithoutResult(s -> audit.log("PAYMENT", payment.getId(), AUDIT_INITIATED, senderId,
                Map.of("threadId", threadId.toString(), "operationId", op.getId().toString(), "provider", op.getProvider(),
                        "msisdnMasked", op.getMsisdnMasked(), "status", op.getStatus().name())));
        if (op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-deposit-rejected",
                    "Deposit Rejected", "Paiement refusé par l'opérateur : "
                    + (op.getFailureMessage() != null ? op.getFailureMessage() : op.getFailureCode()));
        }
        return status(threadId, deadline, Optional.of(payment), Optional.of(op));
    }

    private String resolvePayerMsisdn(UUID senderId, String phoneOverride) {
        if (phoneOverride != null && !phoneOverride.isBlank()) {
            try {
                return Msisdn.normalize(phoneOverride);
            } catch (IllegalArgumentException e) {
                throw payerUnsupported("Numéro de téléphone invalide.");
            }
        }
        String firebasePhone = userRepository.findById(senderId)
                .map(u -> firebaseContact.getContact(u.getFirebaseUid()).phoneNumber()).orElse(null);
        if (firebasePhone == null || firebasePhone.isBlank()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-phone-required",
                    "Phone Required", "Indiquez le numéro mobile money qui paiera.");
        }
        try {
            return Msisdn.normalize(firebasePhone);
        } catch (IllegalArgumentException e) {
            throw payerUnsupported("Le numéro enregistré n'est pas exploitable. Indiquez un autre numéro.");
        }
    }

    // ── Statut ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MobileMoneyNegotiationStatusResponse status(UUID threadId, LocalDateTime deadline) {
        Optional<PaymentEntity> payment = paymentRepository.findByNegotiationThreadId(threadId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY);
        Optional<PawapayOperationEntity> deposit = payment.flatMap(p -> operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT));
        return status(threadId, deadline, payment, deposit);
    }

    private MobileMoneyNegotiationStatusResponse status(UUID threadId, LocalDateTime deadline, Optional<PaymentEntity> payment,
                                                        Optional<PawapayOperationEntity> deposit) {
        var view = deposit.map(o -> new MobileMoneyPaymentStatusResponse.OperationView(
                o.getId(), o.getStatus().name(), o.getProvider(), PawapayProviders.label(o.getProvider()),
                o.getMsisdnMasked(), o.getAuthorizationUrl(), o.getFailureCode(), o.getFailureMessage())).orElse(null);
        return new MobileMoneyNegotiationStatusResponse(threadId, payment.map(p -> p.getStatus().name()).orElse(null), deadline,
                payment.map(PaymentEntity::getAmount).orElse(null), payment.map(PaymentEntity::getCurrency).orElse(null), view);
    }

    // ── Libération (échéance, renoncement de l'expéditeur) ───────────────

    @Transactional
    public NegotiationMobileMoneyPort.ReleaseOutcome releasePendingDeposit(UUID threadId) {
        Optional<PaymentEntity> payment = paymentRepository.findByNegotiationThreadIdForUpdate(threadId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY);
        if (payment.isPresent() && payment.get().getStatus() == PaymentStatus.ESCROW) {
            // Le rappel pawaPay a déjà posé le séquestre (PENDING → ESCROW) et commité, mais
            // le fil n'est pas encore ACCEPTED : le scellement (finalizeAfterMobileMoneyDeposit)
            // est en vol dans sa propre transaction. Rendre NOTHING_PENDING ici ramènerait le
            // fil à AWAITING_PAYMENT pendant qu'un dépôt valide est déjà encaissé, argent
            // engagé, accord perdu. Ne rien faire, alerter.
            log.error("Fil {} : séquestre posé, fil pas encore scellé, libération abandonnée (paiement {})",
                    threadId, payment.get().getId());
            return NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_COMPLETED_NOT_APPLIED;
        }
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

    // ── Séquestre ───────────────────────────────────────────────────────

    /**
     * Deposit COMPLETED : PENDING → ESCROW une seule fois (claim atomique). Jamais de setter sur
     * {@code payment} après le claim. Le scellement du fil est délégué à {@code requests/} par
     * {@link MobileMoneyNegotiationDepositConfirmedEvent} ; si le fil n'est plus scellable, ce
     * package rappelle {@link #refundEscrowedDeposit} par le port.
     */
    @Transactional
    public void confirmEscrow(UUID operationId, UUID paymentId) {
        int moved = paymentRepository.markEscrowIfPending(paymentId, Instant.now());
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Paiement introuvable : " + paymentId));
        if (moved == 0) {
            if (payment.getStatus() == PaymentStatus.CANCELLED) {
                // Échéance passée pendant la saisie du PIN : encaissé quand même, rendu sur-le-champ.
                submitRefund(payment, operations.get(operationId));
            } else {
                log.info("Deposit {} déjà appliqué sur le paiement {} ({})", operationId, paymentId, payment.getStatus());
            }
            return;
        }
        audit.log("PAYMENT", paymentId, AUDIT_CONFIRMED, null, Map.of("threadId", payment.getNegotiationThreadId().toString(),
                "operationId", operationId.toString(), "amount", payment.getAmount().toPlainString(), "currency", payment.getCurrency()));
        events.publishEvent(new MobileMoneyNegotiationDepositConfirmedEvent(payment.getNegotiationThreadId(), paymentId, operationId));
        log.info("Séquestre mobile money : paiement {} ESCROW, fil {}", paymentId, payment.getNegotiationThreadId());
    }

    /** Deposit FAILED : le paiement reste PENDING (nouvel essai possible), le fil est prévenu par événement. */
    @Transactional
    public void notifyDepositFailed(UUID operationId, UUID paymentId, String failureCode) {
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null || payment.getNegotiationThreadId() == null) {
            log.warn("Deposit {} FAILED : paiement {} introuvable ou sans fil, notification abandonnée", operationId, paymentId);
            return;
        }
        String safe = PawapayText.clamp(failureCode);
        audit.log("PAYMENT", paymentId, AUDIT_FAILED, null, Map.of("threadId", payment.getNegotiationThreadId().toString(),
                "operationId", operationId.toString(), "failureCode", safe == null ? "" : safe));
        events.publishEvent(new MobileMoneyNegotiationDepositFailedEvent(payment.getNegotiationThreadId(), paymentId, safe));
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
