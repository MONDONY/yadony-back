package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.fee.CancellationFeeBreakdown;
import com.yadony.api.payments.fee.CancellationFeeSettlement;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chemin unique de remboursement d'un paiement (bid classique ou thread de négociation).
 *
 * <p>Garanties :
 * <ul>
 *   <li>PENDING → le PaymentIntent est annulé (un PI non capturé ne se rembourse pas) ;</li>
 *   <li>ESCROW → claim atomique {@code markRefundedIfEscrow} (anti double-refund intra-instance)
 *       puis, selon l'état réel du PaymentIntent : {@code pi.cancel()} si autorisé non capturé
 *       (requires_capture — cas normal chez Yadony, capture à la livraison seulement), ou
 *       {@code Refund.create} (clé d'idempotence {@code "refund-" + paymentId}) si déjà capturé
 *       (succeeded) ; un PI déjà {@code canceled} est un no-op idempotent ;</li>
 *   <li>retenue d'annulation due par l'expéditeur fautif ({@link CancellationFeeSettlement}) : sur un
 *       PI capturé, {@code Refund.create} du seul montant payé moins le dédommagement du voyageur (même
 *       clé), puis retenue réglée et {@link com.yadony.api.payments.fee.CancellationFeeRetainedEvent} ;
 *       partout ailleurs, remboursement intégral inchangé et retenue close {@code NOT_APPLICABLE}. Un
 *       calcul de retenue qui échoue ne bloque jamais l'expéditeur : remboursement intégral, alerte
 *       {@code FEE_COMPUTE_FAILED_<paymentId>}, retenue close ;</li>
 *   <li>échec Stripe → alerte admin + exception : la transaction REQUIRES_NEW rollback le claim,
 *       le paiement reste remboursable (et la retenue due) ;</li>
 *   <li>RELEASED / REFUNDED / FAILED / CANCELLED → no-op (jamais de refund post-versement).</li>
 * </ul>
 *
 * <p>{@code REQUIRES_NEW} : chaque paiement vit dans sa propre transaction — un échec dans un
 * traitement par lot (annulation de trajet) n'annule pas les remboursements déjà réussis.
 *
 * <p><b>Rail mobile money</b> — {@code payment.getRail() == PAWAPAY} bascule sur un
 * second chemin, complètement séparé du chemin Stripe ci-dessus (aucune ligne du chemin Stripe
 * n'est modifiée par cette branche) : voir {@link #refundMobileMoney}. Les six listeners
 * appelants existants de {@link #processRefund} (annulations, litiges, rejets d'annonce) ne
 * changent pas — le dispatch sur le rail est entièrement interne à cette classe. Même retenue d'annulation sur ce
 * rail : remboursement pawaPay PARTIEL du dépôt (payé moins le dédommagement), frais perdus lus dans le barème
 * pawaPay de l'opérateur du dépôt, mêmes règles de repli (calcul en échec, paiement inéligible : dépôt rendu en
 * entier, retenue close).
 */
@Component
public class RefundProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);

    /**
     * {@code admin_alerts.type} est {@code VARCHAR(60)} : préfixe + UUID (36) doit rester sous
     * 60 ({@link AdminAlertEscalator} refuse bruyamment un type trop long). Dédupliqué PAR
     * PAIEMENT (pas un type global) — sinon la dédup empêcherait à tort l'alerte d'un paiement B
     * sous prétexte qu'un paiement A a déjà le même problème non résolu.
     */
    static final String NO_DEPOSIT_ALERT_PREFIX = "PAWAPAY_REFUND_NO_DEP_";

    /** Idem, pour le refus pawaPay du refund. */
    static final String REJECTED_ALERT_PREFIX = "PAWAPAY_REFUND_REJECTED_";

    /** Idem, pour un remboursement refusé parce qu'un versement existe déjà. */
    static final String PAYOUT_EXISTS_ALERT_PREFIX = "PAWAPAY_REFUND_PAYOUT_";

    /** Annulation d'un PaymentIntent PENDING refusée par Stripe : autorisation restée posée. */
    static final String PENDING_CANCEL_FAILED_ALERT_PREFIX = "REFUND_FAILED_";

    /** Retenue d'annulation incalculable : expéditeur remboursé en entier, voyageur non dédommagé (19 + 36). */
    static final String FEE_COMPUTE_FAILED_ALERT_PREFIX = "FEE_COMPUTE_FAILED_";

    /** Rail mobile money : remboursement déjà en vol d'un autre montant que celui de la retenue (20 + 36). */
    static final String FEE_REFUND_MISMATCH_ALERT_PREFIX = "FEE_REFUND_MISMATCH_";

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlert;
    private final PawapayOperationService pawapayOperations;
    private final PawapaySubmissionService pawapaySubmission;
    private final AdminAlertEscalator alerts;

    /**
     * Transaction INDÉPENDANTE réservée à l'audit du remboursement mobile money — même outil,
     * même motif que {@code MobileMoneyPayoutInitiator#independentAuditTransaction} : la trace
     * d'un remboursement réellement soumis doit survivre même si la transaction ambiante (qui
     * porte le claim) échouait ensuite.
     */
    private final TransactionTemplate independentAuditTransaction;

    private final CancellationFeeSettlement cancellationFees;

    public RefundProcessor(PaymentRepository paymentRepository,
                           AuditService auditService,
                           AdminAlertService adminAlert,
                           PawapayOperationService pawapayOperations,
                           PawapaySubmissionService pawapaySubmission,
                           AdminAlertEscalator alerts,
                           PlatformTransactionManager transactionManager,
                           CancellationFeeSettlement cancellationFees) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.adminAlert = adminAlert;
        this.pawapayOperations = pawapayOperations;
        this.pawapaySubmission = pawapaySubmission;
        this.alerts = alerts;
        this.cancellationFees = cancellationFees;
        this.independentAuditTransaction = new TransactionTemplate(transactionManager);
        this.independentAuditTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @return true si une action Stripe a été exécutée (cancel ou refund), false si no-op.
     * @throws IllegalStateException si Stripe échoue sur le refund ESCROW (rollback du claim).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processRefund(UUID paymentId, String auditAction, UUID auditActor,
                                 Map<String, String> auditPayload) {
        PaymentEntity payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.debug("processRefund: payment {} introuvable — no-op", paymentId);
            return false;
        }

        if (payment.getRail() == PaymentRail.PAWAPAY) {
            return refundMobileMoney(payment, auditAction, auditActor, auditPayload);
        }

        return switch (payment.getStatus()) {
            case PENDING -> cancelPendingPaymentIntent(payment, auditAction, auditActor, auditPayload);
            case ESCROW -> refundEscrowedPayment(payment, paymentId, auditAction, auditActor, auditPayload);
            default -> {
                log.info("processRefund: payment {} en statut {} — aucune action",
                        payment.getId(), payment.getStatus());
                yield false;
            }
        };
    }

    private boolean cancelPendingPaymentIntent(PaymentEntity payment, String auditAction,
                                               UUID auditActor, Map<String, String> auditPayload) {
        try {
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            pi.cancel(PaymentIntentCancelParams.builder()
                    .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                    .build());

            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);

            auditService.log("PAYMENT", payment.getId(), auditAction, auditActor,
                    enrich(auditPayload, payment));
            log.info("PaymentIntent {} annulé ({})", payment.getStripePaymentIntentId(), auditAction);
            return true;
        } catch (StripeException e) {
            log.error("Échec annulation PI {} : {}", payment.getStripePaymentIntentId(), e.getMessage(), e);
            // Plus avalé en silence : l'autorisation reste posée sur la carte de l'expéditeur
            // tant que personne n'agit. Alerte unique par paiement (préfixe 14 + UUID 36 = 50).
            try {
                alerts.raiseOnce(PENDING_CANCEL_FAILED_ALERT_PREFIX + payment.getId(),
                        "Annulation du PaymentIntent " + payment.getStripePaymentIntentId() + " (paiement "
                                + payment.getId() + ") refusée par Stripe : " + e.getMessage()
                                + " ; l'autorisation carte de l'expéditeur reste posée, à annuler à la main",
                        Map.of("paymentId", payment.getId().toString(),
                                "piId", String.valueOf(payment.getStripePaymentIntentId()),
                                "error", String.valueOf(e.getMessage())));
            } catch (RuntimeException alertFailure) {
                log.error("Alerte {} non levée pour le paiement {}", PENDING_CANCEL_FAILED_ALERT_PREFIX,
                        payment.getId(), alertFailure);
            }
            return false;
        }
    }

    private boolean refundEscrowedPayment(PaymentEntity payment, UUID paymentId, String auditAction,
                                          UUID auditActor, Map<String, String> auditPayload) {
        int claimed = paymentRepository.markRefundedIfEscrow(paymentId);
        if (claimed == 0) {
            log.info("Paiement {} déjà sorti d'ESCROW — remboursement ignoré", paymentId);
            return false;
        }

        final String stripeAction;
        final String piStatus;
        Optional<CancellationFeeBreakdown> retainedFee = Optional.empty();
        RuntimeException feeComputationFailure = null;
        try {
            // Chez Yadony, la capture du PaymentIntent n'a lieu qu'à la livraison
            // (DeliveryConfirmedEvent). Un paiement ESCROW correspond donc, dans la
            // quasi-totalité des cas, à un PaymentIntent AUTORISÉ mais NON CAPTURÉ
            // (status=requires_capture). Stripe refuse Refund.create sur une charge
            // non capturée ("You must cancel the PaymentIntent ... instead of
            // refunding the Charge directly") → il faut annuler l'autorisation.
            // On ne rembourse (Refund) que si le PI a réellement été capturé (succeeded).
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            piStatus = pi.getStatus();
            if ("succeeded".equals(piStatus)) {
                // Retenue d'annulation due par l'expéditeur fautif : lue ICI, APRÈS le claim
                // markRefundedIfEscrow ci-dessus, jamais avant. Ce claim garantit un seul remboursement
                // par paiement, donc un seul règlement de la retenue ; la lecture du taux par le port
                // est volontairement sans verrou, le claim la sérialise.
                try {
                    retainedFee = cancellationFees.settle(payment, metadataId(pi, "sender_id"),
                            metadataId(pi, "traveler_id"));
                } catch (RuntimeException e) {
                    // Le calcul de la retenue ne bloque JAMAIS le remboursement de l'expéditeur (taux de
                    // change absent, barème invalide…) : remboursement intégral comme avant, alerte et
                    // clôture de la retenue après le succès Stripe.
                    log.error("Retenue d'annulation incalculable pour le paiement {} : remboursement intégral",
                            paymentId, e);
                    feeComputationFailure = e;
                }
                if (retainedFee.isPresent()) {
                    refundMinusCancellationFee(payment, paymentId, retainedFee.get());
                    stripeAction = "remboursement partiel émis (PI capturé, frais d'annulation retenus)";
                } else {
                    Refund.create(
                            RefundCreateParams.builder()
                                    .setPaymentIntent(payment.getStripePaymentIntentId())
                                    .build(),
                            RequestOptions.builder()
                                    .setIdempotencyKey("refund-" + paymentId)
                                    .build());
                    stripeAction = "remboursement émis (PI capturé)";
                }
            } else if (!"canceled".equals(piStatus)) {
                // requires_capture (et autres états pré-capture) → libère le hold.
                stripeAction = cancelOrRefundIfCapturedMeanwhile(pi, payment.getStripePaymentIntentId(), paymentId);
            } else {
                // PI déjà 'canceled' → no-op idempotent bénin.
                stripeAction = "aucune action (PI déjà annulé)";
            }
        } catch (StripeException e) {
            log.error("Échec libération escrow PI {} : {}",
                    payment.getStripePaymentIntentId(), e.getMessage(), e);
            Map<String, Object> alertCtx = new HashMap<>();
            alertCtx.put("paymentId", paymentId.toString());
            alertCtx.put("piId", payment.getStripePaymentIntentId());
            alertCtx.put("error", String.valueOf(e.getMessage()));
            adminAlert.raise("STRIPE_REFUND_FAILED",
                    "Libération escrow Stripe échouée pour payment " + paymentId,
                    alertCtx);
            throw new IllegalStateException("Stripe escrow release failed for payment " + paymentId, e);
        }

        // Après le succès Stripe, dans la transaction du claim : un échec plus haut a déjà levé, la
        // retenue reste alors due et rien n'est réglé.
        Map<String, Object> enriched = enrich(auditPayload, payment);
        if (retainedFee.isPresent()) {
            CancellationFeeBreakdown fee = retainedFee.get();
            paymentRepository.recordRefundedAmount(paymentId, fee.senderRefund());
            cancellationFees.retain(payment, fee);
            enriched.put("refundedAmount", fee.senderRefund().toPlainString());
            enriched.put("cancellationFee", fee.grossFee().toPlainString());
        } else if (feeComputationFailure != null) {
            raiseFeeComputationAlert(payment, paymentId, feeComputationFailure);
            cancellationFees.waiveAfterFailedComputation(payment);
        } else {
            cancellationFees.waiveIfDue(payment, piStatus);
        }
        auditService.log("PAYMENT", payment.getId(), auditAction, auditActor, enriched);
        log.info("Escrow libéré pour PI {} — {} ({})",
                payment.getStripePaymentIntentId(), stripeAction, auditAction);
        return true;
    }

    /**
     * Rend à l'expéditeur le montant payé moins la retenue ({@code senderRefund}), en unités mineures de la
     * devise du paiement. Même clé d'idempotence que le remboursement intégral : un paiement n'a jamais
     * qu'un remboursement par ce chemin, et un rejeu Stripe rend le même objet.
     */
    private static void refundMinusCancellationFee(PaymentEntity payment, UUID paymentId,
                                                   CancellationFeeBreakdown fee) throws StripeException {
        long amountMinor = CurrencyAmount.of(fee.senderRefund(),
                SupportedCurrency.fromCodeOrDefault(payment.getCurrency())).minor();
        Refund.create(
                RefundCreateParams.builder()
                        .setPaymentIntent(payment.getStripePaymentIntentId())
                        .setAmount(amountMinor)
                        .build(),
                RequestOptions.builder()
                        .setIdempotencyKey("refund-" + paymentId)
                        .build());
    }

    /**
     * Alerte unique par paiement, levée APRÈS le remboursement intégral (un échec Stripe l'aurait sinon
     * annoncé à tort). Une alerte qui échoue ne remet pas en cause le remboursement déjà émis.
     */
    private void raiseFeeComputationAlert(PaymentEntity payment, UUID paymentId, RuntimeException failure) {
        raiseFeeComputationAlert(payment, paymentId, failure, "l'expéditeur a été remboursé en entier");
    }

    /** @param refundOutcome ce que l'expéditeur a reçu, dit en clair dans l'alerte */
    private void raiseFeeComputationAlert(PaymentEntity payment, UUID paymentId, RuntimeException failure,
                                          String refundOutcome) {
        try {
            alerts.raiseOnce(FEE_COMPUTE_FAILED_ALERT_PREFIX + paymentId,
                    "Retenue d'annulation incalculable pour le paiement " + paymentId + " : "
                            + failure.getMessage() + " ; " + refundOutcome + ", le voyageur "
                            + "n'est pas dédommagé, à régler à la main",
                    Map.of("paymentId", paymentId.toString(),
                            "bidId", String.valueOf(payment.getBidId()),
                            "error", String.valueOf(failure.getMessage())));
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {} non levée pour le paiement {}", FEE_COMPUTE_FAILED_ALERT_PREFIX, paymentId,
                    alertFailure);
        }
    }

    /**
     * Rail mobile money : un remboursement d'une tentative précédente est déjà en vol pour un autre montant que
     * l'expéditeur moins la retenue. Il fait foi ; la retenue est close et l'écart est à régler à la main. Une alerte
     * qui échoue ne remet pas en cause le remboursement.
     */
    private void raiseRefundMismatchAlert(PaymentEntity payment, UUID paymentId, PawapayOperationEntity refund,
                                          CancellationFeeBreakdown expected) {
        try {
            alerts.raiseOnce(FEE_REFUND_MISMATCH_ALERT_PREFIX + paymentId,
                    "Retenue d'annulation non réglée pour le paiement " + paymentId + " : un remboursement pawaPay "
                            + refund.getId() + " de " + refund.getAmount().toPlainString() + " " + refund.getCurrency()
                            + " était déjà en vol, " + expected.senderRefund().toPlainString() + " attendus ; retenue "
                            + "close, voyageur non dédommagé, à régler à la main",
                    Map.of("paymentId", paymentId.toString(),
                            "bidId", String.valueOf(payment.getBidId()),
                            "operationId", refund.getId().toString(),
                            "refundAmount", refund.getAmount().toPlainString(),
                            "expectedRefund", expected.senderRefund().toPlainString()));
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {} non levée pour le paiement {}", FEE_REFUND_MISMATCH_ALERT_PREFIX, paymentId,
                    alertFailure);
        }
    }

    /**
     * Expéditeur ou voyageur posé en métadonnée du PaymentIntent à sa création ({@code PaymentService}, bid
     * comme fil de négociation), ou null. Ne sert qu'au repli du taux de commission de la retenue.
     */
    private static UUID metadataId(PaymentIntent pi, String key) {
        Map<String, String> metadata = pi.getMetadata();
        String value = metadata == null ? null : metadata.get(key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Annule l'autorisation. La capture du séquestre ne tient plus de verrou pendant l'appel Stripe
     * (PROD_CHECKLIST 9.23) : une capture concurrente peut passer entre la lecture du PaymentIntent
     * et l'annulation, que Stripe refuse alors. On relit le PaymentIntent : s'il est désormais
     * capturé, on rembourse la charge (même clé {@code refund-<paymentId>}) au lieu d'échouer.
     */
    private String cancelOrRefundIfCapturedMeanwhile(PaymentIntent pi, String piId, UUID paymentId)
            throws StripeException {
        try {
            pi.cancel(PaymentIntentCancelParams.builder()
                    .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                    .build());
            return "autorisation annulée (PI non capturé)";
        } catch (StripeException cancelFailure) {
            PaymentIntent fresh = PaymentIntent.retrieve(piId);
            if (fresh == null || !"succeeded".equals(fresh.getStatus())) {
                throw cancelFailure;
            }
            log.warn("PaymentIntent {} capturé pendant le remboursement du paiement {} : remboursement de la charge",
                    piId, paymentId);
            Refund.create(
                    RefundCreateParams.builder().setPaymentIntent(piId).build(),
                    RequestOptions.builder().setIdempotencyKey("refund-" + paymentId).build());
            return "remboursement émis (PI capturé pendant l'annulation)";
        }
    }

    /**
     * Rail mobile money. {@code PENDING} (jamais encaissé) → {@code CANCELLED}, sans aucun appel
     * pawaPay : si un deposit est encore en vol (par ex. {@code PROCESSING}) et aboutit ensuite,
     * c'est {@code MobileMoneyBidPaymentService#confirmEscrow} qui verra le paiement
     * {@code CANCELLED} et remboursera lui-même ce deposit tardif — volontairement PAS le rôle
     * de cette méthode (voir le Javadoc de {@code confirmEscrow}).
     *
     * <p>{@code ESCROW} → claim atomique {@link PaymentRepository#markRefundedIfEscrow} — LA
     * MÊME primitive que le chemin Stripe ci-dessus, partagée entre les deux rails sur la même
     * colonne {@code status} : un double remboursement reste structurellement impossible quel
     * que soit le rail ou le nombre de chemins d'appel. Puis refund pawaPay du deposit
     * {@code COMPLETED} d'origine (trouvé via {@code findLatest}, jamais recalculé : le montant
     * remboursé est celui du deposit, {@link PawapayOperationEntity#getAmount()} — jamais
     * {@code payment.getAmount()}, égaux aujourd'hui mais sans garantie contractuelle). Un
     * refund déjà vivant ({@code findLive}) est repris tel quel, jamais resoumis (spec §7.1,
     * protégé structurellement par l'index unique partiel {@code uq_pawapay_ops_live_per_payment}).
     * L'absence de deposit {@code COMPLETED} ou un refus pawaPay (statut {@code SUBMIT_REJECTED})
     * remontent en exception : la transaction {@code REQUIRES_NEW} annule alors le claim, le
     * paiement redevient {@code ESCROW} et donc remboursable plus tard.
     *
     * <p>Retenue d'annulation due par l'expéditeur fautif ({@link CancellationFeeSettlement#settleMobileMoney}) :
     * le refund porte sur le dépôt moins la retenue, puis {@code refunded_amount}, retenue réglée et
     * {@link com.yadony.api.payments.fee.CancellationFeeRetainedEvent}, comme le rail carte. Un refus pawaPay du
     * remboursement partiel laisse la retenue due (claim annulé) ; un nouvel essai resoumet le même montant. Pour
     * rembourser le dépôt entier à la place, un administrateur renonce d'abord à la retenue
     * ({@code POST /admin/cancellation-fees/bids/{bidId}/waive}, ligne {@code NOT_APPLICABLE}
     * {@code ADMIN_WAIVED}) : {@code processRefund} rend alors le dépôt entier, comme sans retenue.
     *
     * <p>Cette méthode et {@link #refundEscrowedMobileMoney} ne font JAMAIS
     * {@code payment.setStatus(...)} après un claim bulk — {@code enrich(...)} n'utilise pas
     * {@code status}, rien d'autre ne le lit non plus. Un tel setter rendrait l'entité sale et
     * le flush suivant écraserait le claim avec le statut périmé en mémoire.
     */
    private boolean refundMobileMoney(PaymentEntity payment, String auditAction, UUID auditActor,
                                      Map<String, String> auditPayload) {
        UUID paymentId = payment.getId();
        return switch (payment.getStatus()) {
            case PENDING -> {
                int cancelled = paymentRepository.markCancelledIfPending(paymentId);
                if (cancelled == 0) {
                    yield false;
                }
                // Jamais encaissé : rien à retenir, une retenue encore due est close (sans retenue : une lecture).
                cancellationFees.waiveNotCollected(payment);
                auditService.log("PAYMENT", paymentId, auditAction, auditActor, enrich(auditPayload, payment));
                log.info("Paiement mobile money {} annulé avant encaissement ({})", paymentId, auditAction);
                yield true;
            }
            case ESCROW -> refundEscrowedMobileMoney(payment, paymentId, auditAction, auditActor, auditPayload);
            default -> {
                log.info("processRefund: paiement mobile money {} en statut {} — aucune action",
                        paymentId, payment.getStatus());
                yield false;
            }
        };
    }

    private boolean refundEscrowedMobileMoney(PaymentEntity payment, UUID paymentId, String auditAction,
                                              UUID auditActor, Map<String, String> auditPayload) {
        // EN TÊTE, avant toute soumission — y compris avant le claim ci-dessous. La branche crée
        // elle-même l'état « paiement ESCROW alors qu'un versement est parti »
        // (MobileMoneyPayoutInitiator : soumission acceptée puis timeout HTTP, rollback du
        // claim ; le poller mène ensuite l'opération à COMPLETED pendant que le paiement
        // redevient ESCROW). Sans cette garde, un opérateur voit une fiche qui indique qu'aucun
        // versement n'a été tenté, clique « Rembourser », et le brut repart à l'expéditeur
        // pendant que le net est déjà chez le voyageur — perte sèche, sans alerte. findLive
        // couvre aussi COMPLETED (LIVE_OR_DONE). Les trois alertes de cette méthode précèdent
        // un throw qui annule la transaction ambiante : leur dédup ne tient que parce que
        // AdminAlertEscalator commite sa ligne dans sa propre transaction.
        Optional<PawapayOperationEntity> existingPayout =
                pawapayOperations.findLive(paymentId, PawapayOperationKind.PAYOUT);
        if (existingPayout.isPresent()) {
            PawapayOperationEntity payout = existingPayout.get();
            alerts.raiseOnce(PAYOUT_EXISTS_ALERT_PREFIX + paymentId,
                    "Paiement " + paymentId + " en ESCROW alors qu'un versement pawaPay existe déjà (opération "
                            + payout.getId() + ", statut " + payout.getStatus()
                            + ") : remboursement bloqué, vérification manuelle requise",
                    Map.of("paymentId", paymentId.toString(), "operationId", payout.getId().toString(),
                            "payoutStatus", payout.getStatus().name()));
            throw new IllegalStateException(
                    "pawaPay payout already exists for payment " + paymentId + " — refund refused");
        }

        int claimed = paymentRepository.markRefundedIfEscrow(paymentId);
        if (claimed == 0) {
            log.info("Paiement {} déjà sorti d'ESCROW — remboursement mobile money ignoré", paymentId);
            return false;
        }

        Optional<PawapayOperationEntity> completedDeposit = pawapayOperations
                .findLatest(paymentId, PawapayOperationKind.DEPOSIT)
                .filter(deposit -> deposit.getStatus() == PawapayOperationStatus.COMPLETED);
        if (completedDeposit.isEmpty()) {
            alerts.raiseOnce(NO_DEPOSIT_ALERT_PREFIX + paymentId,
                    "Paiement " + paymentId + " en ESCROW sans deposit pawaPay COMPLETED : remboursement manuel requis",
                    Map.of("paymentId", paymentId.toString()));
            throw new IllegalStateException("No completed pawaPay deposit for payment " + paymentId);
        }
        PawapayOperationEntity deposit = completedDeposit.get();

        // Retenue d'annulation due par l'expéditeur fautif : lue ICI, après le claim, jamais avant (même garantie
        // que le rail carte : un seul remboursement par paiement, donc un seul règlement). Un calcul qui échoue ne
        // bloque jamais l'expéditeur : dépôt rendu en entier comme avant, alerte, retenue close.
        Optional<CancellationFeeBreakdown> retainedFee = Optional.empty();
        RuntimeException feeComputationFailure = null;
        try {
            retainedFee = cancellationFees.settleMobileMoney(payment, deposit.getProvider(), deposit.getAmount());
        } catch (RuntimeException e) {
            log.error("Retenue d'annulation incalculable pour le paiement mobile money {} : remboursement intégral",
                    paymentId, e);
            feeComputationFailure = e;
        }

        // Un refund déjà vivant (tentative précédente soumise puis annulée : réseau muet, commit raté) est repris
        // tel quel, jamais resoumis : son montant fait foi. La retenue n'est réglée que s'il est exactement celui de
        // l'expéditeur moins la retenue ; sinon elle est close avec une alerte, rien n'est rendu deux fois.
        Optional<PawapayOperationEntity> liveRefund = pawapayOperations.findLive(paymentId, PawapayOperationKind.REFUND);
        CancellationFeeBreakdown unmatchedFee = null;
        if (liveRefund.isPresent() && retainedFee.isPresent()
                && retainedFee.get().senderRefund().compareTo(liveRefund.get().getAmount()) != 0) {
            unmatchedFee = retainedFee.get();
            retainedFee = Optional.empty();
        }
        // Remboursement PARTIEL (pawaPay v2 l'accepte : amount + currency) : le dépôt moins la retenue. Sans retenue,
        // le dépôt entier, exactement comme avant.
        BigDecimal refundAmount = retainedFee.map(CancellationFeeBreakdown::senderRefund).orElse(deposit.getAmount());

        // Ce contrôle SUBMIT_REJECTED ne peut jamais se déclencher pour un refund RÉCUPÉRÉ via
        // findLive — LIVE_OR_DONE (l'ensemble filtré par findLive) exclut structurellement
        // SUBMIT_REJECTED (rangé dans DEAD). Il n'est atteignable que pour un refund tout juste
        // soumis par submitRefund ci-dessous. Un refus annule le claim : la retenue reste due, rien
        // n'est réglé ni clos, et un nouvel essai resoumet le même remboursement partiel.
        PawapayOperationEntity refund = liveRefund
                .orElseGet(() -> pawapaySubmission.submitRefund(paymentId, deposit, refundAmount));
        if (refund.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            alerts.raiseOnce(REJECTED_ALERT_PREFIX + paymentId,
                    "pawaPay a refusé le remboursement du paiement " + paymentId + " : " + refund.getFailureCode(),
                    Map.of("paymentId", paymentId.toString(), "operationId", refund.getId().toString(),
                            "failureCode", String.valueOf(refund.getFailureCode())));
            throw new IllegalStateException("pawaPay refund rejected: " + refund.getFailureCode());
        }

        // Après l'acceptation pawaPay, dans la transaction du claim. Un échec ici annule le claim alors que le
        // remboursement est parti : le paiement redevient ESCROW, la retenue reste due, et un nouvel essai reprend
        // ce remboursement vivant (branche ci-dessus) puis règle la retenue.
        Map<String, Object> enriched = enrich(auditPayload, payment);
        enriched.put("refundOperationId", refund.getId().toString());
        if (retainedFee.isPresent()) {
            CancellationFeeBreakdown fee = retainedFee.get();
            paymentRepository.recordRefundedAmount(paymentId, fee.senderRefund());
            cancellationFees.retain(payment, fee);
            enriched.put("refundedAmount", fee.senderRefund().toPlainString());
            enriched.put("cancellationFee", fee.grossFee().toPlainString());
        } else if (feeComputationFailure != null) {
            raiseFeeComputationAlert(payment, paymentId, feeComputationFailure,
                    "l'expéditeur est remboursé de " + refund.getAmount().toPlainString() + " " + refund.getCurrency()
                            + " (remboursement pawaPay " + refund.getId() + ")");
            cancellationFees.waiveAfterFailedComputation(payment);
        } else if (unmatchedFee != null) {
            raiseRefundMismatchAlert(payment, paymentId, refund, unmatchedFee);
            cancellationFees.waiveAfterRefundInFlight(payment);
        } else {
            cancellationFees.waiveMobileMoneyIfDue(payment, deposit.getAmount());
        }

        // Audit dans sa propre transaction — motif repris de MobileMoneyPayoutInitiator#release :
        // la trace du remboursement survit même si la transaction ambiante (le claim) échouait
        // ensuite. Rien de faillible ne suit ce point.
        independentAuditTransaction.executeWithoutResult(status ->
                auditService.log("PAYMENT", paymentId, auditAction, auditActor, enriched));
        log.info("Remboursement mobile money {} soumis pour le paiement {} ({})", refund.getId(), paymentId, auditAction);
        return true;
    }

    private Map<String, Object> enrich(Map<String, String> payload, PaymentEntity payment) {
        Map<String, Object> out = new HashMap<>(payload);
        out.put("piId", payment.getStripePaymentIntentId());
        out.put("amount", payment.getAmount().toPlainString());
        out.put("rail", payment.getRail().name());
        return out;
    }
}
