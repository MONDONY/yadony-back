package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.TransferCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Story 6.4 / bid-checkout-payment-first — Listens to DeliveryConfirmedEvent and
 * releases the Stripe escrow.
 *
 * Two paths depending on payment.legacy_destination_charge:
 *  - legacy=true  : destination charge model — capture the PaymentIntent (Stripe routes
 *                   funds to the traveler's Connect account via transfer_data set at PI
 *                   creation).
 *  - legacy=false : separate charges-and-transfers — the PI is normally captured at
 *                   acceptation (BidAcceptedEventListener) or at escrow (negotiation,
 *                   NegotiationCaptureListener). Still requires_capture here → captured first
 *                   by EscrowCaptureService, then a Transfer to the traveler's Connect account.
 *
 * Cross-package communication via Spring Events only.
 *
 * <p><b>Rail pawaPay</b> : un troisième chemin, gardé par
 * {@code payment.getRail() == PaymentRail.PAWAPAY}, bifurque vers
 * {@link com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator} juste après le
 * claim atomique ci-dessous, avant tout appel Stripe. Aucun appel Stripe, aucun
 * {@link com.yadony.api.payments.events.PaymentReleasedEvent} publié ici pour ce rail :
 * il part plus tard, quand pawaPay confirme le payout
 * ({@code MobileMoneyPayoutOutcomeListener}).
 */
@Component
public class DeliveryEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryEventListener.class);

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final BidRepository bidRepository;
    private final AdminAlertService adminAlert;
    private final com.yadony.api.voucher.CommissionVoucherService voucherService;

    /**
     * Injection par CONSTRUCTEUR, jamais par champ — une dépendance
     * contournable (comme l'était le champ {@code @Autowired} précédent) est une NPE qui
     * attend, si un futur test construit cette classe sans la fournir alors qu'un paiement
     * PAWAPAY lui parvient. {@code DeliveryEventListenerTest} et
     * {@code DeliveryEventListenerChargebackTest} passent tous deux {@code null} explicitement
     * (leurs paiements sont tous de rail {@code STRIPE}, la branche pawaPay n'est jamais
     * atteinte, donc jamais déréférencé).
     */
    private final com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator payoutInitiator;

    private final PayoutHoldPolicy holdPolicy;
    private final AdminAlertEscalator alertEscalator;
    private final EscrowCaptureService escrowCapture;
    private final StripeTransferLookup transferLookup;
    private final com.yadony.api.disputes.DisputeRepository disputeRepository;

    public DeliveryEventListener(PaymentRepository paymentRepository,
                                 UserRepository userRepository,
                                 AuditService auditService,
                                 ApplicationEventPublisher eventPublisher,
                                 BidRepository bidRepository,
                                 AdminAlertService adminAlert,
                                 com.yadony.api.voucher.CommissionVoucherService voucherService,
                                 com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator payoutInitiator,
                                 PayoutHoldPolicy holdPolicy,
                                 AdminAlertEscalator alertEscalator,
                                 EscrowCaptureService escrowCapture,
                                 StripeTransferLookup transferLookup,
                                 com.yadony.api.disputes.DisputeRepository disputeRepository) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.bidRepository = bidRepository;
        this.adminAlert = adminAlert;
        this.voucherService = voucherService;
        this.payoutInitiator = payoutInitiator;
        this.holdPolicy = holdPolicy;
        this.alertEscalator = alertEscalator;
        this.escrowCapture = escrowCapture;
        this.transferLookup = transferLookup;
        this.disputeRepository = disputeRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleDeliveryConfirmed(DeliveryConfirmedEvent event) {
        release(new ReleaseTrigger(event.getBidId(), event.getSenderId(), event.getTravelerId(), SOURCE_DELIVERY));
    }

    /**
     * Colis « non réclamé » au terme de la garde (FLUTTER-E2) : le voyageur, qui a fait le
     * transport, reçoit le net par exactement le même chemin que la livraison — mêmes gardes
     * (séquestre, chargeback, remboursement partiel, voyageur gelé, compte Stripe inutilisable),
     * même claim atomique ESCROW → RELEASED, même clé d'idempotence Stripe {@code transfer-<id>}.
     * Avec le force-release admin, c'est la seule libération autorisée sans
     * {@link DeliveryConfirmedEvent} (décision produit FLUTTER-E2). Une livraison postérieure
     * (retrait par une personne mandatée) ne verse plus rien : le paiement n'est plus ESCROW.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleParcelUnclaimed(com.yadony.api.cancellation.events.ParcelUnclaimedEvent event) {
        release(new ReleaseTrigger(event.bidId(), event.senderId(), event.travelerId(), SOURCE_UNCLAIMED));
    }

    /**
     * Rattrapage : le paiement est passé en séquestre APRÈS la livraison (webhook
     * {@code amount_capturable_updated} tardif, resynchronisation admin ou automatique). Le colis
     * est livré, la livraison n'avait rien versé faute de séquestre : on verse maintenant, par le
     * même chemin et avec les mêmes gardes, pour que l'admin n'ait plus à forcer le versement.
     *
     * <p>Transaction propre ({@code REQUIRES_NEW}), comme le listener de livraison : un échec du
     * Transfer annule le claim, le paiement reste ESCROW. À appeler hors de toute transaction qui
     * tiendrait un verrou sur la ligne {@code payments} (la capture tourne dans une autre).
     *
     * @param source chemin appelant, tracé dans l'audit ({@code late-escrow}, {@code admin-resync-stripe}…)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EscrowReleaseOutcome releaseAfterLateEscrow(java.util.UUID bidId, java.util.UUID senderId,
                                                       java.util.UUID travelerId, String source) {
        EscrowReleaseOutcome outcome = release(new ReleaseTrigger(bidId, senderId, travelerId, source));
        if (outcome.released()) {
            paymentRepository.findByBidId(bidId)
                    .or(() -> bidRepository.findById(bidId)
                            .map(BidEntity::getLinkedNegotiationThreadId)
                            .flatMap(paymentRepository::findByNegotiationThreadId))
                    .ifPresent(p -> resolveNotInEscrowAlert(p.getId()));
        }
        return outcome;
    }

    /** L'alerte « colis livré sans séquestre » n'a plus d'objet une fois le versement parti. */
    private void resolveNotInEscrowAlert(java.util.UUID paymentId) {
        try {
            alertEscalator.resolveOpen(NOT_IN_ESCROW_ALERT_PREFIX + paymentId);
        } catch (RuntimeException e) {
            log.warn("Alerte {}{} non close après le versement : {}", NOT_IN_ESCROW_ALERT_PREFIX, paymentId,
                    e.getMessage());
        }
    }

    static final String SOURCE_DELIVERY = "delivery";
    static final String SOURCE_UNCLAIMED = "unclaimed";
    static final String SOURCE_LATE_ESCROW = "late-escrow";

    /**
     * Alerte « colis livré, paiement jamais passé en séquestre ». {@code admin_alerts.type} est
     * limité à {@link AdminAlertEscalator#TYPE_MAX_LENGTH} caractères : préfixe (20) + UUID (36)
     * = 56. L'ancien préfixe {@code DELIVERY_PAYMENT_NOT_IN_ESCROW_} donnait 67 caractères :
     * {@code raiseOnce} levait une exception, l'alerte ne partait jamais et l'audit était annulé.
     */
    public static final String NOT_IN_ESCROW_ALERT_PREFIX = "DELIVERY_NOT_ESCROW_";

    /** Alerte « versement gelé par un litige admin » : préfixe (20) + UUID (36) = 56 caractères. */
    public static final String DISPUTE_HOLD_ALERT_PREFIX = "DISPUTE_PAYOUT_HOLD_";

    /** Ce qui déclenche la libération : la livraison confirmée ou le colis « non réclamé ». */
    record ReleaseTrigger(java.util.UUID bidId, java.util.UUID senderId, java.util.UUID travelerId, String source) {
        java.util.UUID getBidId() { return bidId; }
        java.util.UUID getSenderId() { return senderId; }
        java.util.UUID getTravelerId() { return travelerId; }
        boolean unclaimed() { return SOURCE_UNCLAIMED.equals(source); }
    }

    private EscrowReleaseOutcome release(ReleaseTrigger event) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid != null && bid.getPaymentMethod() == PaymentMethod.CASH) {
            log.debug("CASH bid {} — no Stripe escrow to release", event.getBidId());
            return EscrowReleaseOutcome.CASH;
        }

        Optional<PaymentEntity> paymentOpt = paymentRepository.findByBidId(event.getBidId());

        // Negotiation / dedicated-trip escrow is keyed on the negotiation thread
        // (bid_id = NULL) — the bid is materialised after payment. Fall back to the
        // thread payment so these escrows are released to the traveler too; otherwise
        // findByBidId returns empty and the payout is silently skipped.
        if (paymentOpt.isEmpty() && bid != null && bid.getLinkedNegotiationThreadId() != null) {
            paymentOpt = paymentRepository.findByNegotiationThreadId(bid.getLinkedNegotiationThreadId());
        }

        if (paymentOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent received for bidId={} but no payment found — skipping",
                    event.getBidId());
            return EscrowReleaseOutcome.NO_PAYMENT;
        }

        PaymentEntity payment = paymentOpt.get();

        if (payment.getStatus() != PaymentStatus.ESCROW) {
            log.info("Payment {} for bid {} has status {} — skipping escrow release",
                    payment.getId(), event.getBidId(), payment.getStatus());
            // Un colis livré dont le paiement n'a jamais atteint le séquestre (PENDING) n'est pas un
            // cas normal : contrairement à un paiement déjà remboursé ou versé, rien ne l'a réglé, et
            // l'autorisation carte expire à J+7 sans que le voyageur soit payé. Il était ignoré en
            // silence (sonde INV-08, paiement 500391e5) : un admin doit trancher.
            if (payment.getStatus() == PaymentStatus.PENDING) {
                auditService.log("PAYMENT", payment.getId(), "DELIVERY_PAYMENT_NOT_IN_ESCROW",
                        event.getBidId(), Map.of("bidId", event.getBidId().toString()));
                alertEscalator.raiseOnce(NOT_IN_ESCROW_ALERT_PREFIX + payment.getId(),
                        "Colis livré mais paiement " + payment.getId() + " jamais passé en séquestre (PENDING) : "
                                + "le voyageur ne sera pas payé, l'autorisation carte expire à J+7",
                        Map.of("paymentId", payment.getId().toString(), "bidId", event.getBidId().toString(),
                                "amount", String.valueOf(payment.getAmount())));
            }
            return EscrowReleaseOutcome.NOT_IN_ESCROW;
        }

        if (payment.isDisputed()) {
            log.warn("Payment {} for bid {} is under chargeback dispute — blocking transfer",
                    payment.getId(), event.getBidId());
            auditService.log("PAYMENT", payment.getId(), "DELIVERY_TRANSFER_BLOCKED_CHARGEBACK",
                    event.getBidId(), Map.of("bidId", event.getBidId().toString()));
            adminAlert.raise("CHARGEBACK_TRANSFER_BLOCKED",
                    "Tentative de liberation escrow bloquee — litige ouvert sur payment " + payment.getId(),
                    Map.of("paymentId", payment.getId().toString(), "bidId", event.getBidId().toString()));
            return EscrowReleaseOutcome.BLOCKED_CHARGEBACK;
        }

        // Litige ouvert par l'administration (POST /admin/bids/{id}/disputes) : le versement est
        // gelé jusqu'à sa résolution. Même modèle que la garde chargeback : rien ne part, le
        // paiement reste ESCROW, un admin tranche dans Incidents. Limité aux litiges ADMIN_* :
        // les litiges d'absence ont leur propre procédure et ne changent pas de comportement.
        if (adminDisputeOpen(event.getBidId())) {
            log.warn("Payment {} for bid {}: admin dispute open — payout frozen", payment.getId(), event.getBidId());
            auditService.log("PAYMENT", payment.getId(), "DELIVERY_TRANSFER_BLOCKED_DISPUTE",
                    event.getBidId(), Map.of("bidId", event.getBidId().toString(), "source", event.source()));
            alertEscalator.raiseOnce(DISPUTE_HOLD_ALERT_PREFIX + payment.getId(),
                    "Versement gelé : un litige ouvert par l'administration est en cours sur le colis "
                            + event.getBidId() + ", le paiement " + payment.getId() + " reste en séquestre",
                    Map.of("paymentId", payment.getId().toString(), "bidId", event.getBidId().toString()));
            return EscrowReleaseOutcome.BLOCKED_DISPUTE;
        }

        // Remboursement partiel déjà passé (charge.refunded non total : le paiement reste ESCROW).
        // Le net versé plus bas part du montant TOTAL : libérer verserait au voyageur la part
        // déjà rendue à l'expéditeur, payée par la plateforme. Même modèle que la garde litige :
        // rien ne part, le paiement reste en séquestre, un admin tranche (alerte persistée).
        if (payment.getRefundedAmount() != null && payment.getRefundedAmount().signum() > 0) {
            log.warn("Payment {} for bid {} was partially refunded ({}) — blocking release",
                    payment.getId(), event.getBidId(), payment.getRefundedAmount());
            auditService.log("PAYMENT", payment.getId(), "DELIVERY_TRANSFER_BLOCKED_PARTIAL_REFUND",
                    event.getBidId(), Map.of("bidId", event.getBidId().toString(),
                            "refundedAmount", payment.getRefundedAmount().toPlainString()));
            alertEscalator.raiseOnce("PARTIAL_REFUND_HOLD_" + payment.getId(),
                    "Versement bloqué : le paiement " + payment.getId() + " a déjà été partiellement remboursé ("
                            + payment.getRefundedAmount().toPlainString() + " " + payment.getCurrency()
                            + " sur " + payment.getAmount().toPlainString() + "), montant à verser à décider",
                    Map.of("paymentId", payment.getId().toString(), "bidId", event.getBidId().toString(),
                            "refundedAmount", payment.getRefundedAmount().toPlainString(),
                            "amount", payment.getAmount().toPlainString()));
            return EscrowReleaseOutcome.BLOCKED_PARTIAL_REFUND;
        }

        // Bénéficiaire gelé (banni ou vérification d'identité retirée) : même modèle que la garde
        // litige ci-dessus — AVANT le claim, le paiement reste ESCROW et rien ne part, sur les
        // trois rails (carte V2, carte legacy, mobile money). Seul un geste admin explicite
        // (force-release avec dérogation) le libère ensuite.
        if (holdPolicy.isHeld(event.getTravelerId())) {
            holdPayout(payment, event);
            return EscrowReleaseOutcome.PAYOUT_HELD;
        }

        // Compte Connect désactivé ou refusé : un Transfer serait refusé par Stripe après le
        // claim (rollback, nouvelle tentative à chaque rejeu). On le constate avant, sans claim.
        if (payment.getRail() != PaymentRail.PAWAPAY && !payment.isLegacyDestinationCharge()
                && stripeAccountUnusable(payment, event)) {
            return EscrowReleaseOutcome.STRIPE_ACCOUNT_UNUSABLE;
        }

        // Séquestre carte (non legacy) : le Transfer ne part que de fonds capturés sur le solde
        // plateforme. Un paiement passé ESCROW sans capture (checkout d'avant #472) est encore une
        // autorisation `requires_capture` : on le capture ici, AVANT le claim (la capture tourne
        // dans sa propre transaction et ne doit attendre aucun verrou posé par celle-ci). Échec :
        // aucun claim, aucun Transfer, le paiement reste ESCROW et une alerte admin est levée.
        String chargeId = payment.getStripeChargeId();
        if (payment.getRail() != PaymentRail.PAWAPAY && !payment.isLegacyDestinationCharge()) {
            try {
                EscrowCaptureService.Outcome captured = escrowCapture.ensureCaptured(payment.getId(), event.source());
                if (captured.chargeId() != null) {
                    chargeId = captured.chargeId();
                }
            } catch (EscrowCaptureService.EscrowCaptureException e) {
                log.warn("Payment {} for bid {} could not be captured ({}) — release skipped, stays ESCROW",
                        payment.getId(), event.getBidId(), e.getMessage());
                auditService.log("PAYMENT", payment.getId(), "DELIVERY_RELEASE_BLOCKED_CAPTURE_FAILED",
                        event.getBidId(), Map.of("bidId", event.getBidId().toString(),
                                "piStatus", String.valueOf(e.getPiStatus()),
                                "reason", String.valueOf(e.getMessage()),
                                "source", event.source()));
                return EscrowReleaseOutcome.CAPTURE_FAILED;
            }
        }

        // Claim atomique ESCROW → RELEASED, PARTAGÉ par les deux rails (Stripe et pawaPay) :
        // empêche un double versement (double capture / double Transfer / double payout) si
        // l'événement de livraison est traité deux fois en parallèle. Le branchement par rail
        // ci-dessous se fait TOUJOURS après ce claim, jamais avant — un seul thread doit
        // pouvoir gagner, quel que soit le rail.
        // Les gardes chargeback / remboursement partiel / retenue sont REVÉRIFIÉES dans l'UPDATE
        // conditionnel : la lecture plus haut n'est qu'un pré-filtre (alerte, audit), jamais la
        // décision de verser.
        int claimed = paymentRepository.markReleasedIfEscrowAndUnguarded(
                payment.getId(), LocalDateTime.now(ZoneOffset.UTC));
        if (claimed == 0) {
            PaymentStatus current = paymentRepository.findStatusById(payment.getId()).orElse(null);
            if (current == PaymentStatus.ESCROW) {
                // Toujours en séquestre : une garde est apparue entre la lecture et le claim
                // (chargeback, remboursement partiel, retenue). Rien ne part.
                log.warn("Payment {} for bid {}: a payout guard appeared concurrently — release skipped, stays ESCROW",
                        payment.getId(), event.getBidId());
                auditService.log("PAYMENT", payment.getId(), "DELIVERY_RELEASE_BLOCKED_CONCURRENT_GUARD",
                        event.getBidId(), Map.of("bidId", event.getBidId().toString(), "source", event.source()));
                return EscrowReleaseOutcome.BLOCKED_CHARGEBACK;
            }
            log.info("Payment {} for bid {} already left ESCROW — skipping release",
                    payment.getId(), event.getBidId());
            return EscrowReleaseOutcome.ALREADY_RELEASED;
        }

        // Litige admin revérifié APRÈS le claim, par une nouvelle lecture : l'ouverture du litige
        // verrouille la ligne du paiement avant d'écrire, le claim ci-dessus a donc attendu son
        // commit et cette lecture le voit. Litige ouvert : le claim est annulé, rien ne part.
        if (adminDisputeOpen(event.getBidId())) {
            paymentRepository.revertReleaseClaim(payment.getId());
            log.warn("Payment {} for bid {}: admin dispute opened concurrently — claim reverted", payment.getId(),
                    event.getBidId());
            auditService.log("PAYMENT", payment.getId(), "DELIVERY_TRANSFER_BLOCKED_DISPUTE",
                    event.getBidId(), Map.of("bidId", event.getBidId().toString(), "source", event.source()));
            return EscrowReleaseOutcome.BLOCKED_DISPUTE;
        }

        if (payment.getRail() == PaymentRail.PAWAPAY) {
            // Rail mobile money : le séquestre est sur le solde pawaPay de yadony, le
            // versement est un payout pawaPay du net. Aucun appel Stripe, aucun
            // PaymentReleasedEvent ici : il part quand pawaPay confirme le payout
            // (MobileMoneyPayoutOutcomeListener). Un échec remonte pour annuler le claim
            // ci-dessus (rollback de la transaction REQUIRES_NEW ambiante).
            releaseMobileMoney(payment, event);
            return EscrowReleaseOutcome.RELEASED;
        }

        try {
            if (payment.isLegacyDestinationCharge()) {
                releaseLegacy(payment);
            } else {
                releaseV2(payment, event, chargeId);
            }
        } catch (StripeException e) {
            log.error("Escrow release failed for payment {} (bid={}, legacy={}): {}",
                    payment.getId(), event.getBidId(),
                    payment.isLegacyDestinationCharge(), e.getMessage(), e);
            // Rollback de la transaction REQUIRES_NEW : le claim ESCROW → RELEASED
            // est annulé, le paiement reste en ESCROW — le scheduler admin J+48
            // garde la main pour retenter la libération.
            throw new IllegalStateException(
                    "Stripe escrow release failed for payment " + payment.getId(), e);
        }

        String action = event.unclaimed()
                ? "ESCROW_RELEASED_UNCLAIMED"
                : payment.isLegacyDestinationCharge()
                        ? "ESCROW_RELEASED_LEGACY"
                        : "ESCROW_RELEASED_TRANSFER";
        // Use event.getBidId() (the delivered bid) for the audit actor/payload: a
        // negotiation/thread payment has a NULL payment.getBidId(), which would both
        // lose the bid reference and NPE on toString().
        auditService.log(
                "PAYMENT",
                payment.getId(),
                action,
                event.getBidId(),
                Map.of(
                        "bidId", event.getBidId().toString(),
                        "piId", payment.getStripePaymentIntentId(),
                        "amount", payment.getAmount().toPlainString(),
                        "legacy", String.valueOf(payment.isLegacyDestinationCharge()),
                        "source", event.source()
                )
        );

        log.info("Escrow released for payment {} (bid={}, legacy={})",
                payment.getId(), event.getBidId(), payment.isLegacyDestinationCharge());

        // Notify traveler of payout (Story 8.2). event.getBidId() — payment.getBidId()
        // is NULL for negotiation/thread payments.
        eventPublisher.publishEvent(PaymentReleasedEvent.card(
                event.getBidId(), event.getTravelerId(), event.getSenderId(), payment.getAmount(),
                payment.getCommissionAmount(), payment.getCurrency()));
        return EscrowReleaseOutcome.RELEASED;
    }

    /**
     * Versement retenu : trace, marque {@code payout_held_at} (le paiement reste ESCROW) et
     * alerte dédupliquée par paiement — un événement de livraison rejoué ne re-poste rien.
     */
    private void holdPayout(PaymentEntity payment, ReleaseTrigger event) {
        PayoutHoldStatus hold = holdPolicy.statusOf(event.getTravelerId());
        String reason = hold.primaryReason() != null ? hold.primaryReason().name() : "";
        String reasons = String.join(",", hold.reasons().stream().map(Enum::name).toList());
        log.warn("Payment {} for bid {}: traveler {} payouts are held ({}) — payout retained in escrow",
                payment.getId(), event.getBidId(), event.getTravelerId(), reasons);
        paymentRepository.markPayoutHeld(payment.getId(), LocalDateTime.now(ZoneOffset.UTC));
        auditService.log("PAYMENT", payment.getId(), "PAYOUT_HELD_BENEFICIARY", event.getBidId(), Map.of(
                "paymentId", payment.getId().toString(),
                "bidId", event.getBidId().toString(),
                "travelerId", event.getTravelerId().toString(),
                "reason", reason,
                "reasons", reasons));
        alertEscalator.raiseOnce("PAYOUT_HELD_" + payment.getId(),
                "Versement retenu : le voyageur " + event.getTravelerId() + " est gelé (" + reasons
                        + "), le paiement " + payment.getId() + " reste en séquestre",
                Map.of("paymentId", payment.getId().toString(), "bidId", event.getBidId().toString(),
                        "travelerId", event.getTravelerId().toString(), "reason", reason));
    }

    /**
     * Statuts Connect qui ne recevront jamais un Transfer : {@code DISABLED} (désactivé par
     * l'administration) et {@code REJECTED} (refusé par Stripe). Un compte en cours d'onboarding,
     * ou dont le statut local n'a pas encore été rafraîchi, est laissé à l'arbitrage de Stripe :
     * un refus y annule le claim comme avant.
     */
    private boolean stripeAccountUnusable(PaymentEntity payment, ReleaseTrigger event) {
        StripeAccountStatus status = userRepository.findById(event.getTravelerId())
                .map(UserEntity::getStripeAccountStatus)
                .orElse(null);
        if (status != StripeAccountStatus.DISABLED && status != StripeAccountStatus.REJECTED) {
            return false;
        }
        log.warn("Payment {} for bid {}: traveler {} Stripe account is {} — transfer not attempted",
                payment.getId(), event.getBidId(), event.getTravelerId(), status);
        auditService.log("PAYMENT", payment.getId(), "PAYOUT_BLOCKED_STRIPE_ACCOUNT_UNUSABLE", event.getBidId(), Map.of(
                "paymentId", payment.getId().toString(),
                "bidId", event.getBidId().toString(),
                "travelerId", event.getTravelerId().toString(),
                "stripeAccountStatus", status.name()));
        alertEscalator.raiseOnce("PAYOUT_STRIPE_UNUSABLE_" + payment.getId(),
                "Versement impossible : le compte Stripe du voyageur " + event.getTravelerId() + " est " + status
                        + ", le paiement " + payment.getId() + " reste en séquestre",
                Map.of("paymentId", payment.getId().toString(), "travelerId", event.getTravelerId().toString(),
                        "stripeAccountStatus", status.name()));
        return true;
    }

    /**
     * Rail pawaPay : même formule de net que {@link #releaseV2}, volontairement
     * recopiée pour laisser le chemin Stripe byte pour byte identique (aucune régression
     * possible sur le rail carte). {@code payoutInitiator.release} porte toute la logique
     * mobile money (compte de versement, payout orphelin, soumission pawaPay) ; un échec y
     * lève une {@link IllegalStateException} qui remonte ici sans être interceptée, pour que
     * la transaction {@code REQUIRES_NEW} ambiante annule le claim posé juste au-dessus.
     */
    private void releaseMobileMoney(PaymentEntity payment, ReleaseTrigger event) {
        BigDecimal net = payment.getAmount().subtract(payment.getCommissionAmount());
        net = net.add(travelerVoucherTopUp(event.getTravelerId(), event.getBidId(), payment.getCommissionAmount()));
        net = com.yadony.api.payments.pawapay.PawapayAmounts.round(net, payment.getCurrency());
        payoutInitiator.release(payment, event.getBidId(), event.getTravelerId(), net, event.source());
        log.info("Escrow released (mobile money) for payment {} (bid={})", payment.getId(), event.getBidId());
    }

    private void releaseLegacy(PaymentEntity payment) throws StripeException {
        // Old destination-charge model: capture the PaymentIntent. Stripe transfers
        // funds directly to the traveler's Connect account because transfer_data was
        // set at PaymentIntent creation.
        PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
        // Clé d'idempotence stable : un AFTER_COMMIT rejoué ou une redelivery de webhook
        // ne déclenche pas une seconde capture côté Stripe.
        pi.capture(PaymentIntentCaptureParams.builder().build(),
                RequestOptions.builder()
                        .setIdempotencyKey("capture-" + payment.getId())
                        .build());
    }

    private void releaseV2(PaymentEntity payment, ReleaseTrigger event, String chargeId) throws StripeException {
        // New separate-charges-and-transfers model: the PI is captured on the platform balance
        // (at acceptation, at escrow, or just above by EscrowCaptureService when it was still
        // requires_capture). Initiate a Transfer to the traveler's Connect account.
        UserEntity traveler = userRepository.findById(event.getTravelerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Traveler not found: " + event.getTravelerId()));

        if (traveler.getStripeAccountId() == null || traveler.getStripeAccountId().isBlank()) {
            throw new IllegalStateException(
                    "Traveler " + traveler.getId() + " has no Stripe Connect account");
        }

        // TODO Q6 (spec bid-checkout-payment-first): si Stripe support révèle des frais
        // de Transfer non-nuls pour les comptes Connect en zone CFA, ajuster le calcul :
        //     net = total - commission - transferFees
        // Pour l'instant on assume Transfers EUR gratuits (zone SEPA / hypothèse MVP).
        BigDecimal net = payment.getAmount().subtract(payment.getCommissionAmount());
        // Bon de parrainage du voyageur (lot 3) : majore le versement de la part de
        // commission que le bon lui épargne. Le brut payé par l'expéditeur ne change
        // pas — seul ce que yadony retient diminue. Best-effort : un souci côté bon ne
        // doit jamais bloquer la libération de l'escrow.
        net = net.add(travelerVoucherTopUp(event.getTravelerId(), event.getBidId(), payment.getCommissionAmount()));
        SupportedCurrency currency = SupportedCurrency.fromCode(payment.getCurrency());
        if (currency == null) {
            currency = SupportedCurrency.EUR;
        }
        long netMinor = CurrencyAmount.of(net, currency).minor();

        TransferCreateParams.Builder builder = TransferCreateParams.builder()
                .setAmount(netMinor)
                .setCurrency(currency.code())
                .setDestination(traveler.getStripeAccountId())
                .putMetadata("bid_id", event.getBidId().toString())
                .putMetadata("payment_id", payment.getId() != null ? payment.getId().toString() : "");

        if (chargeId != null && !chargeId.isBlank()) {
            builder.setSourceTransaction(chargeId);
        }

        // La clé d'idempotence n'est valable que 24 h : au-delà, un rejeu recréerait un
        // Transfer. On vérifie d'abord chez Stripe qu'aucun Transfer n'existe pour ce paiement ;
        // s'il existe, la base est réalignée dessus (le claim RELEASED est déjà posé) au lieu de
        // payer une seconde fois. Une lecture en échec remonte : aucun Transfer, claim annulé.
        Optional<String> existing = transferLookup.findExistingTransfer(
                payment.getId(), traveler.getStripeAccountId(), payment.getCreatedAt());
        if (existing.isPresent()) {
            realignOnExistingTransfer(payment, existing.get(), event.getBidId(), event.source());
            return;
        }

        // Clé d'idempotence stable : un AFTER_COMMIT rejoué ou une redelivery de webhook
        // ne déclenche pas un second Transfer côté Stripe.
        Transfer transfer = Transfer.create(builder.build(),
                RequestOptions.builder()
                        .setIdempotencyKey("transfer-" + payment.getId())
                        .build());
        if (transfer != null && transfer.getId() != null && payment.getId() != null) {
            paymentRepository.recordStripeTransferId(payment.getId(), transfer.getId());
        }
    }

    /** Litige ouvert par l'administration sur ce colis (lecture en base, jamais en cache). */
    private boolean adminDisputeOpen(java.util.UUID bidId) {
        return disputeRepository != null && disputeRepository.existsByBidIdAndStatusAndTypeStartingWith(
                bidId, com.yadony.api.disputes.DisputeTypes.STATUS_OPEN,
                com.yadony.api.disputes.DisputeTypes.ADMIN_PREFIX);
    }

    /**
     * Un Transfer existe déjà chez Stripe pour ce paiement : aucun second Transfer. Le paiement
     * est déjà passé RELEASED par le claim de l'appelant ; on y attache l'identifiant du Transfer
     * existant et on trace le réalignement.
     */
    private void realignOnExistingTransfer(PaymentEntity payment, String transferId,
                                           java.util.UUID bidId, String source) {
        log.warn("Payment {} for bid {}: Stripe Transfer {} already exists — no second Transfer, DB realigned",
                payment.getId(), bidId, transferId);
        paymentRepository.recordStripeTransferId(payment.getId(), transferId);
        auditService.log("PAYMENT", payment.getId(), "TRANSFER_ALREADY_EXISTS_REALIGNED", bidId, Map.of(
                "paymentId", payment.getId().toString(),
                "bidId", String.valueOf(bidId),
                "transferId", transferId,
                "source", source));
    }

    /**
     * Part de {@code commissionAmount} rendue au voyageur s'il détient un bon actif
     * (consommé dans le même geste) — {@code commissionAmount × (1 − facteur)}, donc
     * la moitié avec le facteur par défaut de 0,5. yadony perçoit alors exactement
     * la moitié de la commission, jamais moins.
     */
    private BigDecimal travelerVoucherTopUp(java.util.UUID travelerId, java.util.UUID bidId,
                                             BigDecimal commissionAmount) {
        try {
            return voucherService.consume(travelerId, bidId)
                    .map(v -> commissionAmount.multiply(BigDecimal.ONE.subtract(v.getFactor()))
                            .setScale(2, java.math.RoundingMode.HALF_UP))
                    .orElse(BigDecimal.ZERO);
        } catch (Exception ex) {
            log.error("Échec consommation bon parrainage voyageur={} bid={}: {}", travelerId, bidId, ex.toString());
            return BigDecimal.ZERO;
        }
    }
}
