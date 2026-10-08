package com.yadony.api.payments.split;

import com.stripe.exception.StripeException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Partage chiffré d'un séquestre décidé par l'admin à la résolution d'un litige (FLUTTER-E2) :
 * une part remboursée à l'expéditeur, une part versée au voyageur, la somme ne dépassant jamais
 * le net restant ({@code amount − commission − refunded_amount}). La commission reste acquise à
 * la plateforme ; un reliquat (somme &lt; net) reste aussi sur le solde plateforme.
 *
 * <p>Déroulé, en trois temps séparés pour qu'un échec soit toujours traçable et reprenable :
 * <ol>
 *   <li>{@link #plan} — validations sans effet (rail, séquestre, montants, voyageur payable,
 *       état du PaymentIntent) ;</li>
 *   <li>{@link #claim} — dans la transaction de la décision admin : claim atomique
 *       ESCROW → RELEASED/REFUNDED (plus aucun autre chemin ne peut verser ni rembourser) et
 *       ligne {@code payment_splits} CLAIMED. Commité AVANT tout appel Stripe ;</li>
 *   <li>{@link #execute} — étapes Stripe, chacune commitée avant la suivante : part expéditeur
 *       (Refund partiel si capturé, capture partielle sinon), puis Transfer de la part voyageur.
 *       Un échec laisse la ligne à sa dernière étape réussie avec {@code last_error}, lève une
 *       alerte admin dédupliquée, et {@link #execute} rejoué reprend sans refaire une étape
 *       (recherche préalable de l'objet Stripe déjà créé + clés d'idempotence par partage).</li>
 * </ol>
 *
 * <p>Rails non pris en charge (422 explicite) : espèces (Yadony ne détient pas le prix du
 * transport), mobile money pawaPay (remboursement partiel suivi d'un payout sur le même dépôt
 * non validé avec pawaPay, une seule opération vivante par paiement, et les invariants
 * monétaires ne le prévoient pas), carte « destination charge » legacy (la capture partielle
 * verserait directement au voyageur via {@code transfer_data}).
 */
@Service
public class PaymentSplitService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSplitService.class);
    static final String STALLED_ALERT_PREFIX = "PAYMENT_SPLIT_STALLED_";
    private static final int ERROR_MAX = 1000;

    private final PaymentRepository paymentRepository;
    private final PaymentSplitRepository splitRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final PayoutHoldPolicy holdPolicy;
    private final StripeSplitGateway stripe;
    private final AuditService auditService;
    private final AdminAlertEscalator alerts;
    private final TransactionTemplate stepTransaction;

    public PaymentSplitService(PaymentRepository paymentRepository,
                               PaymentSplitRepository splitRepository,
                               BidRepository bidRepository,
                               AnnouncementRepository announcementRepository,
                               UserRepository userRepository,
                               PayoutHoldPolicy holdPolicy,
                               StripeSplitGateway stripe,
                               AuditService auditService,
                               AdminAlertEscalator alerts,
                               PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.splitRepository = splitRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.holdPolicy = holdPolicy;
        this.stripe = stripe;
        this.auditService = auditService;
        this.alerts = alerts;
        this.stepTransaction = new TransactionTemplate(transactionManager);
        this.stepTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Partage validé, prêt à être réclamé. */
    public record SplitPlan(UUID paymentId, UUID bidId, UUID travelerId, BigDecimal senderRefund,
                            BigDecimal travelerPayout, String currency, PaymentSplitMode mode,
                            BigDecimal netAvailable) {
    }

    /** Ce que l'admin peut répartir sur le colis d'un litige (formulaire). */
    public record SplitAvailability(boolean splittable, String reasonCode, String currency, BigDecimal amount,
                                    BigDecimal commission, BigDecimal refunded, BigDecimal netAvailable,
                                    String rail, String paymentStatus) {
    }

    /** Lecture seule, sans appel Stripe : sert au formulaire de répartition. */
    @Transactional(readOnly = true)
    public SplitAvailability availability(UUID bidId) {
        BidEntity bid = bidId == null ? null : bidRepository.findById(bidId).orElse(null);
        if (bid == null) {
            return new SplitAvailability(false, "split-no-bid", null, null, null, null, null, null, null);
        }
        if (bid.getPaymentMethod() == PaymentMethod.CASH) {
            return new SplitAvailability(false, "split-not-applicable-cash", bid.getCurrency(),
                    null, null, null, null, "CASH", null);
        }
        Optional<PaymentEntity> paymentOpt = paymentRepository.findForBid(bidId);
        if (paymentOpt.isEmpty()) {
            return new SplitAvailability(false, "split-no-payment", bid.getCurrency(), null, null, null, null, null, null);
        }
        PaymentEntity p = paymentOpt.get();
        String reason = null;
        if (p.getRail() == PaymentRail.PAWAPAY) reason = "split-mobile-money-unsupported";
        else if (p.isLegacyDestinationCharge()) reason = "split-legacy-unsupported";
        else if (p.getStatus() != PaymentStatus.ESCROW) reason = "payment-not-in-escrow";
        else if (p.isDisputed()) reason = "payment-disputed";
        else if (splitRepository.findByPaymentId(p.getId()).isPresent()) reason = "split-already-exists";
        BigDecimal net = netAvailable(p);
        if (reason == null && net.signum() <= 0) reason = "split-nothing-available";
        return new SplitAvailability(reason == null, reason, p.getCurrency(), p.getAmount(), p.getCommissionAmount(),
                p.getRefundedAmount() == null ? BigDecimal.ZERO : p.getRefundedAmount(), net,
                p.getRail().name(), p.getStatus().name());
    }

    /** Net restant à répartir : montant − commission − déjà remboursé, jamais négatif. */
    static BigDecimal netAvailable(PaymentEntity p) {
        BigDecimal refunded = p.getRefundedAmount() == null ? BigDecimal.ZERO : p.getRefundedAmount();
        BigDecimal net = p.getAmount().subtract(p.getCommissionAmount()).subtract(refunded);
        return net.signum() < 0 ? BigDecimal.ZERO : net;
    }

    /** Validations complètes, sans effet de bord (un seul appel Stripe en lecture). */
    @Transactional(readOnly = true)
    public SplitPlan plan(UUID bidId, BigDecimal senderRefund, BigDecimal travelerPayout) {
        if (senderRefund == null || travelerPayout == null) {
            throw unprocessable("split-amounts-required", "Split Amounts Required",
                    "Indiquez la part remboursée à l'expéditeur et la part versée au voyageur (0 possible).");
        }
        if (senderRefund.signum() < 0 || travelerPayout.signum() < 0) {
            throw unprocessable("split-amount-negative", "Negative Split Amount", "Les montants ne peuvent pas être négatifs.");
        }
        if (senderRefund.add(travelerPayout).signum() == 0) {
            throw unprocessable("split-amount-zero", "Empty Split", "Au moins un des deux montants doit être positif.");
        }
        SplitAvailability a = availability(bidId);
        if (!a.splittable()) {
            throw refusal(a.reasonCode());
        }
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(a.currency());
        if (senderRefund.stripTrailingZeros().scale() > currency.minorUnit()
                || travelerPayout.stripTrailingZeros().scale() > currency.minorUnit()) {
            throw unprocessable("split-amount-precision", "Invalid Precision",
                    "Montant trop précis pour la devise " + currency.code() + ".");
        }
        if (senderRefund.add(travelerPayout).compareTo(a.netAvailable()) > 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "split-exceeds-net",
                    "Split Exceeds Net", "La somme des deux parts dépasse le net disponible ("
                    + a.netAvailable().toPlainString() + " " + a.currency() + ").",
                    Map.of("netAvailable", a.netAvailable().toPlainString(), "currency", a.currency()));
        }

        PaymentEntity payment = paymentRepository.findForBid(bidId).orElseThrow();
        BidEntity bid = bidRepository.findById(bidId).orElseThrow();
        UUID travelerId = announcementRepository.findById(bid.getAnnouncementId())
                .map(an -> an.getTravelerId()).orElse(null);
        if (travelerPayout.signum() > 0) {
            requirePayableTraveler(travelerId);
        }

        PaymentSplitMode mode;
        try {
            String status = stripe.retrievePaymentIntent(payment.getStripePaymentIntentId()).status();
            if ("succeeded".equals(status)) mode = PaymentSplitMode.REFUND_TRANSFER;
            else if ("requires_capture".equals(status)) mode = PaymentSplitMode.PARTIAL_CAPTURE;
            else throw unprocessable("split-payment-intent-state", "Unexpected Payment State",
                        "État Stripe du paiement inattendu (" + status + ") : partage impossible.");
        } catch (StripeException e) {
            throw new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "stripe-error", "Stripe Error",
                    "Impossible de lire le paiement chez Stripe, réessayez.");
        }
        return new SplitPlan(payment.getId(), bidId, travelerId, senderRefund, travelerPayout,
                payment.getCurrency(), mode, a.netAvailable());
    }

    /**
     * Claim atomique et ligne de partage, dans la transaction de la décision admin (obligatoire :
     * décision et sortie du séquestre sont indissociables).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentSplitEntity claim(SplitPlan plan, UUID disputeId, UUID adminId) {
        PaymentEntity payment = paymentRepository.findById(plan.paymentId()).orElseThrow();
        BigDecimal refundedBefore = payment.getRefundedAmount() == null ? BigDecimal.ZERO : payment.getRefundedAmount();
        boolean travelerPaid = plan.travelerPayout().signum() > 0;
        int claimed = paymentRepository.claimForSplit(plan.paymentId(),
                travelerPaid ? PaymentStatus.RELEASED : PaymentStatus.REFUNDED,
                travelerPaid ? LocalDateTime.now(ZoneOffset.UTC) : null,
                refundedBefore.add(plan.senderRefund()));
        if (claimed == 0) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "payment-not-in-escrow", "Invalid Status",
                    "Le paiement a quitté le séquestre entre-temps : partage refusé.");
        }
        PaymentSplitEntity split = new PaymentSplitEntity();
        split.setPaymentId(plan.paymentId());
        split.setDisputeId(disputeId);
        split.setBidId(plan.bidId());
        split.setTravelerId(plan.travelerId());
        split.setSenderRefundAmount(plan.senderRefund());
        split.setTravelerPayoutAmount(plan.travelerPayout());
        split.setCurrency(plan.currency());
        split.setMode(plan.mode());
        split.setStatus(PaymentSplitStatus.CLAIMED);
        split.setDecidedBy(adminId);
        PaymentSplitEntity saved = splitRepository.save(split);

        Map<String, Object> payload = new HashMap<>();
        payload.put("splitId", saved.getId().toString());
        payload.put("disputeId", String.valueOf(disputeId));
        payload.put("senderRefund", plan.senderRefund().toPlainString());
        payload.put("travelerPayout", plan.travelerPayout().toPlainString());
        payload.put("netAvailable", plan.netAvailable().toPlainString());
        payload.put("currency", plan.currency());
        payload.put("mode", plan.mode().name());
        auditService.log("PAYMENT", plan.paymentId(), "PAYMENT_SPLIT_DECIDED", adminId, payload);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentSplitEntity> findForDispute(UUID disputeId) {
        return splitRepository.findByDisputeId(disputeId);
    }

    /**
     * Exécute (ou reprend) les étapes Stripe d'un partage. Jamais d'exception sur un échec Stripe :
     * la ligne garde sa dernière étape réussie, {@code last_error} et une alerte admin ; l'appelant
     * relit le statut.
     */
    public PaymentSplitEntity execute(UUID splitId) {
        PaymentSplitEntity split = splitRepository.findById(splitId).orElseThrow();
        if (split.getStatus() == PaymentSplitStatus.COMPLETED) {
            return split;
        }
        PaymentEntity payment = paymentRepository.findById(split.getPaymentId()).orElseThrow();
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(split.getCurrency());
        String piId = payment.getStripePaymentIntentId();
        String sid = split.getId().toString();
        PaymentSplitStatus reached = split.getStatus();
        try {
            String charge = payment.getStripeChargeId();
            if (split.getStatus() == PaymentSplitStatus.CLAIMED) {
                SenderResult sender = senderStep(split, payment, currency, piId, sid);
                boolean captured = split.getMode() == PaymentSplitMode.PARTIAL_CAPTURE;
                split = saveStep(splitId, s -> {
                    s.setStatus(PaymentSplitStatus.SENDER_REFUNDED);
                    s.setStripeRefundId(sender.refundId());
                    s.setStripeCaptureDone(captured);
                });
                reached = split.getStatus();
                if (sender.charge() != null) charge = sender.charge();
            }
            if (split.getStatus() == PaymentSplitStatus.SENDER_REFUNDED) {
                String transferId = travelerStep(split, payment, currency, piId, sid, charge);
                split = saveStep(splitId, s -> {
                    s.setStatus(PaymentSplitStatus.COMPLETED);
                    s.setStripeTransferId(transferId);
                    s.setLastError(null);
                    s.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
                });
                final PaymentSplitEntity done = split;
                stepTransaction.executeWithoutResult(t -> auditService.log("PAYMENT", payment.getId(),
                        "PAYMENT_SPLIT_COMPLETED", done.getDecidedBy(), completedPayload(done)));
            }
            return split;
        } catch (StripeException | IllegalStateException e) {
            String error = String.valueOf(e.getMessage());
            log.error("Partage {} du paiement {} interrompu après l'étape {} : {}", splitId, payment.getId(),
                    reached, error);
            PaymentSplitEntity failed = saveStep(splitId, s -> {
                s.setAttempts(s.getAttempts() + 1);
                s.setLastError(error.length() > ERROR_MAX ? error.substring(0, ERROR_MAX) : error);
            });
            alerts.raiseOnce(STALLED_ALERT_PREFIX + payment.getId(),
                    "Partage du paiement " + payment.getId() + " interrompu à l'étape " + failed.getStatus()
                            + " : reprendre depuis le litige (" + error + ")",
                    Map.of("paymentId", payment.getId().toString(), "splitId", sid,
                            "status", failed.getStatus().name()));
            return failed;
        }
    }

    /** Résultat de la part expéditeur : refund créé ou retrouvé, charge source du Transfer. */
    private record SenderResult(String refundId, String charge) {
    }

    private SenderResult senderStep(PaymentSplitEntity split, PaymentEntity payment, SupportedCurrency currency,
                                    String piId, String sid) throws StripeException {
        long refundMinor = CurrencyAmount.of(split.getSenderRefundAmount(), currency).minor();
        if (split.getMode() == PaymentSplitMode.REFUND_TRANSFER) {
            String refundId = null;
            if (refundMinor > 0) {
                Optional<String> existing = stripe.findRefund(piId, sid);
                refundId = existing.isPresent() ? existing.get()
                        : stripe.createRefund(piId, refundMinor, metadata(split, payment), "split-refund-" + sid);
            }
            return new SenderResult(refundId, null);
        }
        // Capture partielle : on capture tout sauf la part expéditeur, Stripe libère le reste.
        long amountMinor = CurrencyAmount.of(payment.getAmount(), currency).minor();
        long toCapture = amountMinor - refundMinor;
        StripeSplitGateway.PaymentIntentState pi = stripe.retrievePaymentIntent(piId);
        if ("requires_capture".equals(pi.status())) {
            pi = stripe.capture(piId, toCapture, "split-capture-" + sid);
        }
        if (!"succeeded".equals(pi.status()) || pi.amountReceived() == null || pi.amountReceived() != toCapture) {
            throw new IllegalStateException("Capture partielle incohérente : statut " + pi.status()
                    + ", encaissé " + pi.amountReceived() + " au lieu de " + toCapture);
        }
        return new SenderResult(null, pi.latestCharge());
    }

    private String travelerStep(PaymentSplitEntity split, PaymentEntity payment, SupportedCurrency currency,
                                String piId, String sid, String charge) throws StripeException {
        long payoutMinor = CurrencyAmount.of(split.getTravelerPayoutAmount(), currency).minor();
        if (payoutMinor == 0) {
            return null;
        }
        String group = "split-" + sid;
        Optional<String> existing = stripe.findTransfer(group);
        if (existing.isPresent()) {
            return existing.get();
        }
        UserEntity traveler = split.getTravelerId() == null ? null
                : userRepository.findById(split.getTravelerId()).orElse(null);
        if (traveler == null || traveler.getStripeAccountId() == null || traveler.getStripeAccountId().isBlank()) {
            throw new IllegalStateException("Voyageur sans compte Stripe Connect : transfert impossible");
        }
        String source = charge;
        if (source == null || source.isBlank()) {
            source = stripe.retrievePaymentIntent(piId).latestCharge();
        }
        return stripe.createTransfer(payoutMinor, currency.code(), traveler.getStripeAccountId(), source, group,
                metadata(split, payment), "split-transfer-" + sid);
    }

    private PaymentSplitEntity saveStep(UUID splitId, java.util.function.Consumer<PaymentSplitEntity> change) {
        return stepTransaction.execute(t -> {
            PaymentSplitEntity s = splitRepository.findById(splitId).orElseThrow();
            change.accept(s);
            return splitRepository.save(s);
        });
    }

    private void requirePayableTraveler(UUID travelerId) {
        if (travelerId == null) {
            throw unprocessable("traveler-not-found", "Invalid Traveler", "Voyageur introuvable pour ce colis.");
        }
        if (holdPolicy.isHeld(travelerId)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "payout-beneficiary-held",
                    "Payout Beneficiary Held",
                    "Les versements de ce voyageur sont gelés : part voyageur impossible (mettez-la à 0).");
        }
        UserEntity traveler = userRepository.findById(travelerId).orElse(null);
        if (traveler == null || traveler.getStripeAccountId() == null || traveler.getStripeAccountId().isBlank()
                || traveler.getStripeAccountStatus() == StripeAccountStatus.DISABLED
                || traveler.getStripeAccountStatus() == StripeAccountStatus.REJECTED) {
            throw unprocessable("traveler-no-connect", "Invalid Traveler",
                    "Le voyageur n'a pas de compte Stripe utilisable : part voyageur impossible.");
        }
    }

    private static Map<String, String> metadata(PaymentSplitEntity split, PaymentEntity payment) {
        Map<String, String> m = new HashMap<>();
        m.put("split_id", split.getId().toString());
        m.put("payment_id", payment.getId().toString());
        m.put("bid_id", String.valueOf(split.getBidId()));
        m.put("dispute_id", String.valueOf(split.getDisputeId()));
        m.put("source", "admin-dispute-split");
        return m;
    }

    private static Map<String, Object> completedPayload(PaymentSplitEntity s) {
        Map<String, Object> m = new HashMap<>();
        m.put("splitId", s.getId().toString());
        m.put("stripeRefundId", String.valueOf(s.getStripeRefundId()));
        m.put("stripeTransferId", String.valueOf(s.getStripeTransferId()));
        m.put("senderRefund", s.getSenderRefundAmount().toPlainString());
        m.put("travelerPayout", s.getTravelerPayoutAmount().toPlainString());
        m.put("currency", s.getCurrency());
        return m;
    }

    private static YadonyBusinessException refusal(String code) {
        return switch (code) {
            case "split-not-applicable-cash" -> unprocessable(code, "Not Applicable",
                    "Envoi payé en espèces : Yadony ne détient pas le prix du transport, rien à répartir.");
            case "split-mobile-money-unsupported" -> unprocessable(code, "Mobile Money Unsupported",
                    "Partage non pris en charge pour un paiement mobile money : utilisez le remboursement ou la "
                            + "libération intégrale depuis la fiche paiement.");
            case "split-legacy-unsupported" -> unprocessable(code, "Legacy Payment Unsupported",
                    "Paiement carte ancien modèle (destination charge) : partage non pris en charge.");
            case "payment-not-in-escrow" -> unprocessable(code, "Invalid Status",
                    "Le paiement n'est plus en séquestre : rien à répartir.");
            case "payment-disputed" -> new YadonyBusinessException(HttpStatus.CONFLICT, code, "Payment Disputed",
                    "Chargeback en cours sur ce paiement : partage refusé.");
            case "split-already-exists" -> new YadonyBusinessException(HttpStatus.CONFLICT, code,
                    "Split Already Exists", "Un partage existe déjà pour ce paiement.");
            case "split-nothing-available" -> unprocessable(code, "Nothing Available",
                    "Aucun net restant à répartir sur ce paiement.");
            default -> unprocessable(code, "Split Unavailable", "Aucun paiement à répartir pour ce litige.");
        };
    }

    private static YadonyBusinessException unprocessable(String code, String title, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, title, detail);
    }
}
