package com.yadony.api.payments.mobilemoney;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.hold.PayoutHeldException;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutAccounts.PayoutDestination;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Versement du net au voyageur, partagé par la livraison, le force-release et la relance
 * admin. L'appelant a DÉJÀ gagné le claim ESCROW → RELEASED avant d'invoquer {@link #release} :
 * ici on garantit qu'aucun second payout vivant n'est jamais soumis pour le même paiement
 * (spec §7.1 : le lien qui fait foi est {@code pawapay_operations.payment_id}) et qu'un échec
 * remonte en exception pour que l'appelant annule son claim.
 *
 * <p>Cette classe n'écrit JAMAIS sur l'entité {@link PaymentEntity} reçue : après le claim
 * bulk de l'appelant ({@code markReleasedIfEscrow}, {@code @Modifying} sans
 * {@code clearAutomatically}), l'entité chargée en amont garde un snapshot périmé
 * ({@code status = ESCROW}) ; {@code PaymentEntity} n'a ni {@code @DynamicUpdate} ni
 * {@code @Version}, le moindre setter la rendrait sale et le flush régénérerait un UPDATE de
 * TOUTES les colonnes — {@code status = 'ESCROW'} écraserait le {@code RELEASED} qui vient
 * d'être posé.
 *
 * <p>Variante {@link #releaseCancellationFee} : versement du dédommagement d'annulation, rattaché à la retenue et non
 * au paiement (aucun claim, purpose {@code CANCELLATION_FEE}, jamais de {@code payment_id}).
 */
@Component
public class MobileMoneyPayoutInitiator {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPayoutInitiator.class);

    /**
     * {@code admin_alerts.type} est {@code VARCHAR(60)} : préfixe + UUID (36) doit rester sous
     * 60 avec marge — {@code PAWAPAY_PAYOUT_ORPHANED_} + UUID ferait exactement 60 (marge
     * nulle). Celui-ci fait 17 + 36 = 53. Voir {@code MobileMoneyPayoutInitiatorTest}.
     */
    static final String ORPHAN_ALERT_PREFIX = "MM_PAYOUT_ORPHAN_";
    /** Payout refusé : un remboursement pawaPay du même paiement est vivant ou abouti. */
    static final String REFUND_EXISTS_ALERT_PREFIX = "MM_PAYOUT_REFUND_EXISTS_";

    /**
     * Versements morts (échec, refus) d'un même dédommagement au-delà desquels plus aucun n'est soumis
     * automatiquement : intervention manuelle (même principe que le plafond des suppléments).
     */
    static final int MAX_DEAD_FEE_PAYOUTS = 3;

    /** {@code clientReferenceId} pawaPay d'un dédommagement : 4 + 36 caractères, comme {@code bid-<bidId>}. */
    static final String CANCELLATION_FEE_REFERENCE_PREFIX = "fee-";

    private final MobileMoneyPayoutAccounts accounts;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final AdminAlertService adminAlert;
    private final AdminAlertEscalator alerts;
    private final AuditService audit;

    /**
     * Transaction INDÉPENDANTE réservée à l'audit d'un versement : si cet INSERT échouait dans
     * la transaction ambiante (celle qui porte le claim), son rollback dé-réclamerait le
     * paiement (retour en ESCROW) alors que l'argent est déjà parti chez pawaPay (soumission
     * acceptée) ou déjà identifié comme parti (rattachement orphelin) — sans laisser la moindre
     * trace de cette tentative.
     */
    private final TransactionTemplate independentAuditTransaction;

    private final PayoutHoldPolicy holdPolicy;
    private final PaymentRepository payments;

    public MobileMoneyPayoutInitiator(MobileMoneyPayoutAccounts accounts, PawapayOperationService operations,
                                      PawapaySubmissionService submission, AdminAlertService adminAlert,
                                      AdminAlertEscalator alerts, AuditService audit,
                                      PlatformTransactionManager transactionManager,
                                      PayoutHoldPolicy holdPolicy, PaymentRepository payments) {
        this.holdPolicy = holdPolicy;
        this.payments = payments;
        this.accounts = accounts;
        this.operations = operations;
        this.submission = submission;
        this.adminAlert = adminAlert;
        this.alerts = alerts;
        this.audit = audit;
        this.independentAuditTransaction = new TransactionTemplate(transactionManager);
        this.independentAuditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Verse {@code net} au voyageur {@code travelerId} pour {@code payment}. Trois issues
     * possibles :
     * <ol>
     *   <li><b>payout déjà vivant ou abouti</b> (examiné AVANT la destination : une reprise ne soumet jamais, donc
     *       elle reste possible même si plus aucun compte ne correspond) ({@link PawapayOperationService#findLive}) :
     *       une soumission antérieure a déjà été acceptée par pawaPay (relance après un
     *       claim annulé pour une raison indépendante, ou événement de livraison rejoué) —
     *       on le reprend tel quel, on n'en soumet JAMAIS un second. Alerte
     *       {@code MM_PAYOUT_ORPHAN_<paymentId>}, dédupliquée ({@link AdminAlertEscalator}) :
     *       la relance admin rappelle {@code release()} sur ce même payout vivant à chaque
     *       tentative d'un opérateur — sans dédup, chaque tentative posterait sur Telegram ;</li>
     *   <li><b>destination absente</b> (aucun payout vivant) : le versement part sur le compte du voyageur du MÊME code
     *       pawaPay que le dépôt du paiement (opérateur et pays), dans la devise du paiement ; à
     *       défaut sur le compte repris par la migration dans cette devise, avec SON numéro et SON
     *       code (jamais le code du dépôt) ({@link MobileMoneyPayoutAccounts#destinationFor}). Sans
     *       aucun des deux (compte retiré entre le séquestre et la livraison, autre devise, autre
     *       opérateur) : alerte {@code PAWAPAY_PAYOUT_NO_ACCOUNT}, exception, AUCUN appel pawaPay ;</li>
     *   <li><b>soumission</b> : {@link PawapaySubmissionService#submitPayout} appelle
     *       pawaPay. Un {@code SUBMIT_REJECTED} (rien n'est parti) lève après une alerte
     *       {@code PAWAPAY_PAYOUT_REJECTED}. Un {@code ACCEPTED} est audité dans
     *       {@link #independentAuditTransaction} — après ce point, la seule chose qui suit
     *       sur le chemin nominal est un {@code log.info}, qui ne peut rien faire annuler.</li>
     * </ol>
     *
     * <p>Sans dérogation : refuse ({@link PayoutHeldException}) un voyageur gelé ou un paiement en
     * litige. Voir {@link #release(PaymentEntity, UUID, UUID, BigDecimal, String, boolean)}.
     */
    public PawapayOperationEntity release(PaymentEntity payment, UUID bidId, UUID travelerId, BigDecimal net, String source) {
        return release(payment, bidId, travelerId, net, source, false);
    }

    /**
     * @param holdOverridden {@code true} uniquement quand un administrateur a explicitement
     *                       dérogé au gel ou au litige (motif audité par l'appelant)
     */
    public PawapayOperationEntity release(PaymentEntity payment, UUID bidId, UUID travelerId, BigDecimal net, String source,
                                          boolean holdOverridden) {
        // Défense en profondeur : aucun appelant ne verse à un voyageur gelé, ni sur un paiement
        // en litige, sans poser explicitement la dérogation (force-release / relance admin
        // motivés et audités). Refusé AVANT tout appel pawaPay.
        if (!holdOverridden && (payment.isDisputed() || holdPolicy.isHeld(travelerId))) {
            log.warn("Payout mobile money refusé pour le paiement {} ({}) : {}", payment.getId(), source,
                    payment.isDisputed() ? "paiement en litige" : "voyageur gelé");
            throw new PayoutHeldException("Payout blocked for payment " + payment.getId()
                    + (payment.isDisputed() ? ": payment disputed" : ": traveler " + travelerId + " payouts are held"));
        }
        // Miroir de RefundProcessor#refundEscrowedMobileMoney (remboursement refusé si un payout
        // existe). Un remboursement dont la réponse pawaPay a expiré annule le claim REFUNDED alors
        // que l'opération aboutit quand même : le paiement redevient ESCROW. Verser ici paierait le
        // voyageur pendant que l'expéditeur est remboursé du brut. findLive couvre COMPLETED.
        // Seul compte le remboursement du dépôt qui finance ce séquestre : un paiement de
        // négociation est recyclé (CANCELLED → PENDING, même identifiant) et peut porter le
        // remboursement d'un dépôt tardif de la tentative précédente.
        Optional<PawapayOperationEntity> refund = operations.findLive(payment.getId(), PawapayOperationKind.REFUND)
                .filter(r -> refundsTheFundingDeposit(payment.getId(), r));
        if (refund.isPresent()) {
            PawapayOperationEntity op = refund.get();
            alerts.raiseOnce(REFUND_EXISTS_ALERT_PREFIX + payment.getId(),
                    "Payout bloqué pour le paiement " + payment.getId() + " : un remboursement pawaPay existe déjà"
                            + " (opération " + op.getId() + ", statut " + op.getStatus() + "), vérification manuelle requise",
                    Map.of("paymentId", payment.getId().toString(), "operationId", op.getId().toString(),
                            "refundStatus", op.getStatus().name(), "source", source));
            throw new IllegalStateException(
                    "pawaPay refund already exists for payment " + payment.getId() + " — payout refused");
        }

        Optional<PawapayOperationEntity> live = operations.findLive(payment.getId(), PawapayOperationKind.PAYOUT);
        if (live.isPresent()) {
            PawapayOperationEntity op = live.get();
            independentAuditTransaction.executeWithoutResult(status -> audit.log("PAYMENT", payment.getId(),
                    "ESCROW_RELEASED_MOBILE_MONEY_RECOVERED", bidId,
                    Map.of("bidId", String.valueOf(bidId), "operationId", op.getId().toString(), "source", source)));
            alerts.raiseOnce(ORPHAN_ALERT_PREFIX + payment.getId(),
                    "Payout déjà en vol repris pour le paiement " + payment.getId() + " (" + op.getStatus() + ")",
                    Map.of("paymentId", payment.getId().toString(), "operationId", op.getId().toString(), "source", source));
            return op;
        }

        // Code du dépôt du paiement : il désigne le compte qui reçoit (même opérateur, même pays).
        // Le dépôt vivant ou abouti, jamais un dépôt échoué plus récent : même règle que le verrou
        // des comptes (MobileMoneyPayoutAccountRepository#countParcelsAwaitingPayout).
        String depositProvider = operations.findLive(payment.getId(), PawapayOperationKind.DEPOSIT)
                .map(PawapayOperationEntity::getProvider).orElse(null);
        Optional<PayoutDestination> destination = accounts.destinationFor(travelerId, depositProvider, payment.getCurrency());
        if (destination.isEmpty()) {
            adminAlert.raise("PAWAPAY_PAYOUT_NO_ACCOUNT",
                    "Versement impossible : le voyageur n'a pas de compte mobile money actif compatible "
                            + "avec ce paiement (payment " + payment.getId() + ")",
                    Map.of("paymentId", payment.getId().toString(), "travelerId", travelerId.toString(), "source", source));
            throw new IllegalStateException("Traveler " + travelerId + " has no payout account matching deposit provider "
                    + depositProvider + " and currency " + payment.getCurrency());
        }
        PayoutDestination to = destination.get();

        PawapayOperationEntity op = submission.submitPayout(payment.getId(), to.msisdn(), to.provider(), to.country(),
                net, payment.getCurrency(), "bid-" + bidId);
        if (op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            adminAlert.raise("PAWAPAY_PAYOUT_REJECTED",
                    "pawaPay a refusé le payout du paiement " + payment.getId() + " : " + op.getFailureCode(),
                    Map.of("paymentId", payment.getId().toString(), "operationId", op.getId().toString(),
                            "failureCode", String.valueOf(op.getFailureCode()), "source", source));
            throw new IllegalStateException("pawaPay payout rejected: " + op.getFailureCode());
        }
        independentAuditTransaction.executeWithoutResult(status -> audit.log("PAYMENT", payment.getId(),
                "ESCROW_RELEASED_MOBILE_MONEY", bidId,
                Map.of("bidId", String.valueOf(bidId), "operationId", op.getId().toString(), "net", net.toPlainString(),
                        "currency", payment.getCurrency(), "msisdnMasked", op.getMsisdnMasked(), "source", source,
                        "provider", to.provider(), "accountId", to.accountId().toString())));
        log.info("Payout mobile money {} soumis pour le paiement {} ({})", op.getId(), payment.getId(), source);
        return op;
    }

    /**
     * Verse au voyageur {@code travelerId} le dédommagement d'annulation {@code feeId} ({@code net} dans
     * {@code currency}), rail mobile money. Variante de {@link #release} rattachée à la RETENUE, jamais au paiement :
     * le paiement du colis est déjà remboursé (en partie) à l'expéditeur, il n'y a ni claim {@code ESCROW → RELEASED}
     * ni versement de colis à protéger. L'opération porte le purpose {@code CANCELLATION_FEE} et
     * {@code cancellation_fee_id} (V391), jamais {@code payment_id} : elle ne peut pas heurter le versement d'un colis
     * ({@code uq_pawapay_ops_live_per_payment}) et les écouteurs du paiement principal l'ignorent.
     *
     * <ol>
     *   <li><b>versement déjà vivant ou abouti</b> pour cette retenue : repris tel quel, jamais un second (rejeu de
     *       l'événement, passage du planificateur, deux essais concurrents) ; l'index
     *       {@code uq_pawapay_ops_live_per_cancellation_fee} tranche une course ;</li>
     *   <li><b>voyageur gelé</b>, ou trop de versements morts ({@value #MAX_DEAD_FEE_PAYOUTS}) : refus sans appel
     *       pawaPay. Le plafond borne le risque d'un versement classé mort par le poller puis accepté tard par
     *       pawaPay, doublé par le nouvel essai ;</li>
     *   <li><b>destination</b> : le compte du voyageur du même code pawaPay que le dépôt du colis, dans la devise de
     *       la retenue, sinon le compte repris par la migration ({@link MobileMoneyPayoutAccounts#destinationFor}).
     *       Aucun : refus {@code NO_MOBILE_MONEY_ACCOUNT}, sans appel pawaPay ;</li>
     *   <li><b>soumission</b> : un refus pawaPay ({@code SUBMIT_REJECTED}, rien n'est parti) est un refus ; pawaPay muet
     *       après la réservation laisse l'opération au poller, rendue telle quelle (jamais resoumise sous un autre
     *       identifiant). Acceptée : auditée dans une transaction indépendante.</li>
     * </ol>
     *
     * <p>Aucune alerte ici : l'appelant ({@code CancellationFeePayoutService}) met la retenue en attente et lève UNE
     * alerte par retenue, quel que soit le nombre d'essais horaires.
     *
     * @return l'opération soumise, ou reprise (son statut dit si l'argent est déjà arrivé)
     * @throws MobileMoneyPayoutRefusedException rien n'est parti, motif dans {@link MobileMoneyPayoutRefusedException#reason()}
     */
    public PawapayOperationEntity releaseCancellationFee(UUID feeId, UUID bidId, UUID travelerId, BigDecimal net,
                                                         String currency) {
        Optional<PawapayOperationEntity> live = operations.findLiveForCancellationFee(feeId);
        if (live.isPresent()) {
            log.info("Dédommagement {} : versement pawaPay {} déjà {}, repris sans seconde soumission", feeId,
                    live.get().getId(), live.get().getStatus());
            return live.get();
        }
        if (holdPolicy.isHeld(travelerId)) {
            throw new MobileMoneyPayoutRefusedException("PAYOUT_HELD",
                    "Versement du dédommagement " + feeId + " refusé : voyageur " + travelerId + " gelé");
        }
        long dead = operations.countDeadForCancellationFee(feeId);
        if (dead >= MAX_DEAD_FEE_PAYOUTS) {
            throw new MobileMoneyPayoutRefusedException("TOO_MANY_FAILED_PAYOUTS",
                    "Versement du dédommagement " + feeId + " : " + dead + " versements morts, plus d'essai automatique");
        }
        // Code du dépôt du colis : il désigne le compte qui reçoit (même opérateur, même pays), comme pour le colis.
        String depositProvider = payments.findForBid(bidId)
                .filter(p -> p.getRail() == PaymentRail.PAWAPAY)
                .flatMap(p -> operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT))
                .map(PawapayOperationEntity::getProvider)
                .orElse(null);
        PayoutDestination to = accounts.destinationFor(travelerId, depositProvider, currency)
                .orElseThrow(() -> new MobileMoneyPayoutRefusedException("NO_MOBILE_MONEY_ACCOUNT",
                        "Voyageur " + travelerId + " sans compte mobile money actif en " + currency
                                + " pour le dédommagement " + feeId));

        PawapayOperationEntity op;
        try {
            op = submission.submitCancellationFeePayout(feeId, travelerId, to.msisdn(), to.provider(), to.country(),
                    net, currency, CANCELLATION_FEE_REFERENCE_PREFIX + feeId);
        } catch (RuntimeException e) {
            // Réservée puis pawaPay muet (ou course perdue contre un essai concurrent) : l'opération vivante fait foi.
            Optional<PawapayOperationEntity> reserved = operations.findLiveForCancellationFee(feeId);
            if (reserved.isPresent()) {
                log.warn("Dédommagement {} : versement pawaPay {} sans réponse, laissé au poller ({})", feeId,
                        reserved.get().getId(), e.toString());
                return reserved.get();
            }
            throw e;
        }
        if (op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            throw new MobileMoneyPayoutRefusedException("PAWAPAY_REJECTED_" + op.getFailureCode(),
                    "pawaPay a refusé le versement du dédommagement " + feeId + " : " + op.getFailureCode());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cancellationFeeId", feeId.toString());
        payload.put("operationId", op.getId().toString());
        payload.put("net", net.toPlainString());
        payload.put("currency", currency);
        payload.put("msisdnMasked", String.valueOf(op.getMsisdnMasked()));
        payload.put("provider", to.provider());
        payload.put("accountId", to.accountId().toString());
        independentAuditTransaction.executeWithoutResult(status -> audit.log("BID", bidId,
                "CANCELLATION_FEE_PAYOUT_SUBMITTED", null, payload));
        log.info("Dédommagement {} : versement mobile money {} soumis pour le colis {}", feeId, op.getId(), bidId);
        return op;
    }

    /**
     * Vrai si {@code refund} rend le dépôt qui finance le séquestre (dernier dépôt vivant ou
     * abouti), ou si aucun dépôt n'est retrouvé : dans le doute, le versement est bloqué.
     */
    private boolean refundsTheFundingDeposit(UUID paymentId, PawapayOperationEntity refund) {
        return operations.findLive(paymentId, PawapayOperationKind.DEPOSIT)
                .map(deposit -> deposit.getId().equals(refund.getRelatedOperationId()))
                .orElse(true);
    }
}
