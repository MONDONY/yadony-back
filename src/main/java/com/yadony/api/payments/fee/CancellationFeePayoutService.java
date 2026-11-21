package com.yadony.api.payments.fee;

import com.stripe.exception.StripeException;
import com.stripe.model.Transfer;
import com.stripe.model.TransferCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutRefusedException;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Versement au voyageur du dédommagement d'annulation retenu sur le remboursement d'un expéditeur fautif, rail
 * carte : Transfer Stripe Connect du net figé au règlement ({@code travelerNet}, devise du paiement) vers le compte
 * Connect du voyageur, depuis la charge du colis quand elle est connue ({@code source_transaction}).
 *
 * <p><b>Jamais deux versements.</b> Clé d'idempotence stable {@code cancel-fee-transfer-<feeId>} pour tous les
 * essais (écouteur, rejeu, planificateur) ; Stripe ne la garde que 24 h, donc chaque essai cherche d'abord le
 * Transfer du groupe {@code cancel_fee_<feeId>} (même parade que {@code StripeTransferLookup} pour la livraison) et
 * le reprend s'il existe. Une recherche impossible n'envoie rien. La ligne passe en {@code PAID_OUT} sous verrou et
 * n'en sort plus : un rejeu sur une ligne versée ne fait rien.
 *
 * <p><b>Jamais bloquée en {@code RETAINED} après un essai.</b> Garde non remplie, refus Stripe ou erreur imprévue :
 * la ligne passe en {@code PAYOUT_PENDING} et une alerte admin unique ({@link #ALERT_PREFIX} + id de la retenue)
 * est levée ; le planificateur horaire reprend la ligne avec la même clé. Si même cette écriture échoue (base
 * indisponible), le planificateur reprend aussi les lignes {@code RETAINED} réglées depuis plus de 10 minutes.
 * L'argent n'est jamais rendu à l'expéditeur : il reste sur le solde de la plateforme jusqu'au versement.
 *
 * <p><b>Gardes</b>, celles du versement d'un colis livré ({@code DeliveryEventListener}) : litige bancaire sur le
 * paiement, litige ouvert par l'administration sur le colis, voyageur gelé, compte Connect désactivé ou refusé,
 * compte absent. Un compte en cours d'onboarding est laissé à l'arbitrage de Stripe, comme à la livraison.
 *
 * <p><b>Transactions.</b> Aucune autour de l'appel Stripe (PROD_CHECKLIST 9.23) : lectures et transitions ont
 * chacune une transaction {@code REQUIRES_NEW} courte. Les lectures aussi : appelé après le commit du
 * remboursement, le contexte de persistance de la transaction terminée est encore lié au fil, avec le paiement
 * chargé avant son claim (statut périmé).
 *
 * <p><b>Rail mobile money</b> : versement pawaPay rattaché à la retenue
 * ({@code MobileMoneyPayoutInitiator#releaseCancellationFee}, purpose {@code CANCELLATION_FEE}, au plus un versement
 * vivant par retenue), une fois le remboursement pawaPay de l'expéditeur abouti. L'issue est asynchrone : la ligne
 * passe en {@code PAID_OUT} à la confirmation ({@link #mobileMoneyPayoutCompleted}, écouteur
 * {@link CancellationFeePayoutOutcomeListener}), en attente à l'échec. Mêmes gardes communes (paiement remboursé,
 * litige, voyageur gelé), même alerte unique, même planificateur.
 */
@Component
public class CancellationFeePayoutService {

    private static final Logger log = LoggerFactory.getLogger(CancellationFeePayoutService.class);

    /** Clé d'idempotence Stripe du Transfer, identique pour tous les essais d'une même retenue. */
    public static final String IDEMPOTENCY_KEY_PREFIX = "cancel-fee-transfer-";
    /** Alerte admin d'un versement en attente : préfixe (18) + UUID de la retenue (36) = 54, sous les 60 de la colonne. */
    public static final String ALERT_PREFIX = "CANCEL_FEE_PAYOUT_";
    /** Groupe Stripe des Transfers d'une retenue : retrouvé après l'expiration de la clé d'idempotence. */
    static final String TRANSFER_GROUP_PREFIX = "cancel_fee_";
    /** Métadonnée {@code source} du Transfer, comme les versements de colis ({@code delivery}, {@code deposit}…). */
    static final String SOURCE = "cancellation_fee";

    private final CancellationFeePayoutPort port;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final PayoutHoldPolicy holdPolicy;
    private final DisputeRepository disputes;
    private final AdminAlertEscalator alerts;
    private final MobileMoneyPayoutInitiator mobileMoneyPayouts;
    private final PawapayOperationService pawapayOperations;
    /** Transactions courtes et neuves ({@code REQUIRES_NEW}) : jamais de transaction autour d'un appel Stripe. */
    private final TransactionOperations transaction;

    @Autowired
    public CancellationFeePayoutService(CancellationFeePayoutPort port, PaymentRepository payments,
                                        UserRepository users, PayoutHoldPolicy holdPolicy,
                                        DisputeRepository disputes, AdminAlertEscalator alerts,
                                        MobileMoneyPayoutInitiator mobileMoneyPayouts,
                                        PawapayOperationService pawapayOperations,
                                        PlatformTransactionManager transactionManager) {
        this(port, payments, users, holdPolicy, disputes, alerts, mobileMoneyPayouts, pawapayOperations,
                requiresNew(transactionManager));
    }

    CancellationFeePayoutService(CancellationFeePayoutPort port, PaymentRepository payments,
                                 UserRepository users, PayoutHoldPolicy holdPolicy,
                                 DisputeRepository disputes, AdminAlertEscalator alerts,
                                 MobileMoneyPayoutInitiator mobileMoneyPayouts,
                                 PawapayOperationService pawapayOperations,
                                 TransactionOperations transaction) {
        this.port = port;
        this.payments = payments;
        this.users = users;
        this.holdPolicy = holdPolicy;
        this.disputes = disputes;
        this.alerts = alerts;
        this.mobileMoneyPayouts = mobileMoneyPayouts;
        this.pawapayOperations = pawapayOperations;
        this.transaction = transaction;
    }

    /**
     * Versement pawaPay du dédommagement confirmé (l'argent est sur le compte du voyageur) : ligne {@code PAID_OUT}, la
     * référence est l'identifiant de l'opération pawaPay, l'alerte ouverte est close. Sans effet sur une ligne qui
     * n'est plus à verser. Ne lève jamais : appelé par l'écouteur de l'issue pawaPay.
     */
    public void mobileMoneyPayoutCompleted(UUID feeId, UUID operationId) {
        try {
            PayableCancellationFee fee = transaction.execute(tx -> port.findPayable(feeId).orElse(null));
            if (fee == null) {
                log.info("Dédommagement {} : versement pawaPay {} abouti, ligne déjà versée ou close", feeId, operationId);
                return;
            }
            paidOut(fee, operationId.toString());
        } catch (RuntimeException e) {
            log.error("Dédommagement {} : versement pawaPay {} abouti mais ligne non mise à jour, le passage horaire "
                    + "retrouvera l'opération aboutie", feeId, operationId, e);
        }
    }

    /**
     * Versement pawaPay du dédommagement échoué (rien n'est parti) : ligne en attente, une seule alerte ; le passage
     * horaire soumet une nouvelle opération (plafond de versements morts dans {@code MobileMoneyPayoutInitiator}).
     * Ne lève jamais.
     */
    public void mobileMoneyPayoutFailed(UUID feeId, String failureCode) {
        PayableCancellationFee fee;
        try {
            fee = transaction.execute(tx -> port.findPayable(feeId).orElse(null));
        } catch (RuntimeException e) {
            log.error("Dédommagement {} : échec pawaPay {} non appliqué à la ligne", feeId, failureCode, e);
            return;
        }
        if (fee == null) {
            log.info("Dédommagement {} : échec pawaPay {} sur une ligne déjà versée ou close", feeId, failureCode);
            return;
        }
        leavePending(feeId, fee, "PAWAPAY_PAYOUT_FAILED_" + failureCode);
    }

    static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Verse le dédommagement de la retenue {@code feeId}, ou le met en attente. Ne lève jamais : à appeler hors de
     * toute transaction (après commit, planificateur).
     *
     * @return {@code true} si la retenue est versée à l'issue de l'appel ; {@code false} si elle est en attente, si
     *         son versement pawaPay est soumis mais pas encore confirmé (ou attend le remboursement de l'expéditeur),
     *         ou si elle n'était plus à verser (déjà versée, close, absente)
     */
    public boolean payOut(UUID feeId) {
        PayableCancellationFee fee = null;
        try {
            fee = transaction.execute(tx -> port.findPayable(feeId).orElse(null));
            if (fee == null) {
                log.debug("Dédommagement {} : plus rien à verser (déjà versé, clos ou absent)", feeId);
                return false;
            }
            return attempt(fee);
        } catch (RuntimeException e) {
            log.error("Versement du dédommagement {} en échec imprévu : mis en attente", feeId, e);
            return leavePending(feeId, fee, "UNEXPECTED_ERROR: " + describe(e));
        }
    }

    private boolean attempt(PayableCancellationFee fee) {
        int sign = fee.travelerNet().signum();
        if (sign == 0) {
            // Petit colis : frais de remboursement et commission ont absorbé toute la retenue.
            return paidOut(fee, null);
        }
        if (sign < 0) {
            return leavePending(fee.feeId(), fee, "NEGATIVE_NET");
        }
        Plan plan = transaction.execute(tx -> plan(fee));
        if (plan.waitingFor() != null) {
            // Remboursement de l'expéditeur encore en vol : rien d'anormal, ni attente ni alerte. La ligne reste telle
            // quelle et le passage horaire la reprend (RETAINED de plus de 10 min, ou déjà PAYOUT_PENDING).
            log.info("Dédommagement {} du colis {} : {}, versement remis au prochain passage", fee.feeId(), fee.bidId(),
                    plan.waitingFor());
            return false;
        }
        if (plan.blockedReason() != null) {
            return leavePending(fee.feeId(), fee, plan.blockedReason());
        }
        return plan.mobileMoney() ? mobileMoneyPayout(fee) : transfer(fee, plan);
    }

    /** Ce qu'il faut pour verser, ou la raison de ne pas le faire. Lectures seules, dans une transaction neuve. */
    private record Plan(String blockedReason, String waitingFor, boolean mobileMoney, UUID paymentId, String chargeId,
                        String destination, CurrencyAmount amount) {

        static Plan blocked(String reason) {
            return new Plan(reason, null, false, null, null, null, null);
        }

        static Plan waiting(String what) {
            return new Plan(null, what, false, null, null, null, null);
        }

        static Plan mobileMoney(UUID paymentId) {
            return new Plan(null, null, true, paymentId, null, null, null);
        }
    }

    private Plan plan(PayableCancellationFee fee) {
        SupportedCurrency currency = SupportedCurrency.fromCode(fee.currency());
        if (currency == null) {
            return Plan.blocked("UNSUPPORTED_CURRENCY_" + fee.currency());
        }
        PaymentEntity payment = payments.findForBid(fee.bidId()).orElse(null);
        if (payment == null) {
            return Plan.blocked("NO_PAYMENT");
        }
        // La retenue est réglée par le remboursement partiel, qui passe le paiement en REFUNDED.
        if (payment.getStatus() != PaymentStatus.REFUNDED) {
            return Plan.blocked("PAYMENT_" + payment.getStatus());
        }
        if (payment.isDisputed()) {
            return Plan.blocked("CHARGEBACK");
        }
        if (disputes.existsByBidIdAndStatusAndTypeStartingWith(fee.bidId(), DisputeTypes.STATUS_OPEN,
                DisputeTypes.ADMIN_PREFIX)) {
            return Plan.blocked("ADMIN_DISPUTE_OPEN");
        }
        PayoutHoldStatus hold = holdPolicy.statusOf(fee.travelerId());
        if (hold.held()) {
            return Plan.blocked("PAYOUT_HELD_" + hold.reasons().stream().map(Enum::name)
                    .collect(Collectors.joining(",")));
        }
        if (payment.getRail() == PaymentRail.PAWAPAY) {
            return mobileMoneyPlan(payment);
        }
        UserEntity traveler = users.findById(fee.travelerId()).orElse(null);
        if (traveler == null) {
            return Plan.blocked("TRAVELER_NOT_FOUND");
        }
        StripeAccountStatus accountStatus = traveler.getStripeAccountStatus();
        if (accountStatus == StripeAccountStatus.DISABLED || accountStatus == StripeAccountStatus.REJECTED) {
            return Plan.blocked("STRIPE_ACCOUNT_" + accountStatus.name());
        }
        String destination = traveler.getStripeAccountId();
        if (destination == null || destination.isBlank()) {
            return Plan.blocked("NO_CONNECT_ACCOUNT");
        }
        return new Plan(null, null, false, payment.getId(), payment.getStripeChargeId(), destination,
                CurrencyAmount.of(fee.travelerNet(), currency));
    }

    /**
     * Rail mobile money : le remboursement pawaPay de l'expéditeur est asynchrone. Le voyageur n'est payé qu'une fois
     * ce remboursement ABOUTI et du montant enregistré ({@code refunded_amount}, le dépôt moins la retenue) : un
     * remboursement échoué, puis relancé pour le dépôt entier, ne doit jamais coexister avec un dédommagement versé.
     */
    private Plan mobileMoneyPlan(PaymentEntity payment) {
        PawapayOperationEntity refund = pawapayOperations.findLatest(payment.getId(), PawapayOperationKind.REFUND)
                .orElse(null);
        if (refund == null) {
            return Plan.blocked("SENDER_REFUND_MISSING");
        }
        if (!refund.getStatus().isFinal()) {
            return Plan.waiting("remboursement de l'expéditeur " + refund.getId() + " " + refund.getStatus());
        }
        if (refund.getStatus() != PawapayOperationStatus.COMPLETED) {
            return Plan.blocked("SENDER_REFUND_" + refund.getStatus().name());
        }
        BigDecimal recorded = payment.getRefundedAmount();
        if (recorded == null || refund.getAmount().compareTo(recorded) != 0) {
            return Plan.blocked("SENDER_REFUND_AMOUNT_MISMATCH");
        }
        return Plan.mobileMoney(payment.getId());
    }

    /**
     * Versement pawaPay, hors transaction : opération vivante de la retenue reprise, sinon soumise. Soumise ou en vol,
     * la ligne attend l'issue pawaPay ({@link #mobileMoneyPayoutCompleted}) ; déjà aboutie (issue perdue), elle est
     * versée ici. Refus ou erreur : attente et alerte, nouvel essai au passage horaire.
     */
    private boolean mobileMoneyPayout(PayableCancellationFee fee) {
        PawapayOperationEntity op;
        try {
            op = mobileMoneyPayouts.releaseCancellationFee(fee.feeId(), fee.bidId(), fee.travelerId(), fee.travelerNet(),
                    fee.currency());
        } catch (MobileMoneyPayoutRefusedException e) {
            log.warn("Dédommagement {} du colis {} : versement pawaPay refusé ({})", fee.feeId(), fee.bidId(),
                    e.getMessage());
            return leavePending(fee.feeId(), fee, e.reason());
        } catch (RuntimeException e) {
            log.warn("Dédommagement {} du colis {} : versement pawaPay en erreur", fee.feeId(), fee.bidId(), e);
            return leavePending(fee.feeId(), fee, "PAWAPAY_ERROR: " + describe(e));
        }
        if (op.getStatus() == PawapayOperationStatus.COMPLETED) {
            return paidOut(fee, op.getId().toString());
        }
        log.info("Dédommagement {} du colis {} : versement pawaPay {} {}, issue attendue", fee.feeId(), fee.bidId(),
                op.getId(), op.getStatus());
        return false;
    }

    /** Appel Stripe, hors transaction : Transfer déjà présent repris, sinon créé sous la clé stable. */
    private boolean transfer(PayableCancellationFee fee, Plan plan) {
        String existing;
        try {
            existing = existingTransfer(fee.feeId());
        } catch (StripeException e) {
            return leavePending(fee.feeId(), fee, "STRIPE_LOOKUP_FAILED: " + describe(e));
        }
        if (existing != null) {
            log.warn("Dédommagement {} : Transfer {} déjà créé chez Stripe, aucun second envoi", fee.feeId(), existing);
            return paidOut(fee, existing);
        }
        Transfer created;
        try {
            created = Transfer.create(transferParams(fee, plan),
                    RequestOptions.builder().setIdempotencyKey(IDEMPOTENCY_KEY_PREFIX + fee.feeId()).build());
        } catch (StripeException e) {
            log.warn("Dédommagement {} du colis {} : Transfer refusé par Stripe ({})", fee.feeId(), fee.bidId(),
                    describe(e));
            return leavePending(fee.feeId(), fee, "STRIPE_ERROR: " + describe(e));
        }
        String transferId = created != null ? created.getId() : null;
        if (transferId == null) {
            return leavePending(fee.feeId(), fee, "STRIPE_NO_TRANSFER_ID");
        }
        return paidOut(fee, transferId);
    }

    private static TransferCreateParams transferParams(PayableCancellationFee fee, Plan plan) {
        TransferCreateParams.Builder builder = TransferCreateParams.builder()
                .setAmount(plan.amount().minor())
                .setCurrency(plan.amount().currency().code())
                .setDestination(plan.destination())
                .setTransferGroup(TRANSFER_GROUP_PREFIX + fee.feeId())
                .putMetadata("bid_id", fee.bidId().toString())
                .putMetadata("fee_id", fee.feeId().toString())
                .putMetadata("payment_id", plan.paymentId().toString())
                .putMetadata("source", SOURCE);
        if (plan.chargeId() != null && !plan.chargeId().isBlank()) {
            builder.setSourceTransaction(plan.chargeId());
        }
        return builder.build();
    }

    /** Transfer non annulé du groupe de la retenue, ou {@code null}. */
    private static String existingTransfer(UUID feeId) throws StripeException {
        TransferCollection page = Transfer.list(TransferListParams.builder()
                .setTransferGroup(TRANSFER_GROUP_PREFIX + feeId)
                .setLimit(10L)
                .build());
        if (page == null || page.getData() == null) {
            return null;
        }
        return page.getData().stream()
                .filter(t -> !Boolean.TRUE.equals(t.getReversed()))
                .map(Transfer::getId)
                .findFirst()
                .orElse(null);
    }

    private boolean paidOut(PayableCancellationFee fee, String payoutRef) {
        boolean paid = Boolean.TRUE.equals(transaction.execute(tx -> port.markPaidOut(fee.feeId(), payoutRef)));
        if (!paid) {
            // Ligne sortie du cycle entre la lecture et l'écriture (supprimée, close) : à regarder si de l'argent est parti.
            log.error("Dédommagement {} du colis {} : ligne plus à verser au moment de l'enregistrer (versement {})",
                    fee.feeId(), fee.bidId(), payoutRef);
            return false;
        }
        log.info("Dédommagement {} versé au voyageur {} pour le colis {} : {} {} ({})", fee.feeId(), fee.travelerId(),
                fee.bidId(), fee.travelerNet().toPlainString(), fee.currency(),
                payoutRef != null ? payoutRef : "rien à transférer");
        resolveAlert(fee.feeId());
        return true;
    }

    /**
     * Ligne en attente et alerte admin (une seule ouverte par retenue, {@link AdminAlertEscalator#raiseOnce}). Ne lève
     * jamais. Aucune alerte si la ligne n'est plus à verser (versée entre-temps par un essai concurrent).
     */
    private boolean leavePending(UUID feeId, PayableCancellationFee fee, String reason) {
        boolean pending;
        try {
            pending = Boolean.TRUE.equals(transaction.execute(tx -> port.markPayoutPending(feeId, reason)));
        } catch (RuntimeException e) {
            log.error("Dédommagement {} non mis en attente ({}) : le passage horaire reprendra la ligne", feeId, reason, e);
            pending = true;
        }
        if (pending) {
            log.warn("Dédommagement {} en attente de versement : {}", feeId, reason);
            raiseAlert(feeId, fee, reason);
        }
        return false;
    }

    private void raiseAlert(UUID feeId, PayableCancellationFee fee, String reason) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("feeId", feeId.toString());
            if (fee != null) {
                context.put("bidId", fee.bidId().toString());
                context.put("travelerId", fee.travelerId().toString());
                context.put("travelerNet", fee.travelerNet().toPlainString());
                context.put("currency", fee.currency());
            }
            context.put("reason", reason);
            String what = fee == null ? "la retenue " + feeId
                    : fee.travelerNet().toPlainString() + " " + fee.currency() + " au voyageur " + fee.travelerId()
                            + " (colis " + fee.bidId() + ")";
            alerts.raiseOnce(ALERT_PREFIX + feeId,
                    "Dédommagement d'annulation non versé : " + what + ", motif " + reason
                            + ". La somme reste chez Yadony, nouvel essai chaque heure.",
                    context);
        } catch (RuntimeException e) {
            log.error("Alerte {}{} non levée", ALERT_PREFIX, feeId, e);
        }
    }

    private void resolveAlert(UUID feeId) {
        try {
            alerts.resolveOpen(ALERT_PREFIX + feeId);
        } catch (RuntimeException e) {
            log.warn("Alerte {}{} non close après le versement : {}", ALERT_PREFIX, feeId, e.getMessage());
        }
    }

    private static String describe(Exception e) {
        String code = e instanceof StripeException stripe && stripe.getCode() != null ? " " + stripe.getCode() : "";
        return e.getClass().getSimpleName() + code + ": " + e.getMessage();
    }
}
