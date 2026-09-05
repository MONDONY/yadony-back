package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
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
 *   <li>échec Stripe → alerte admin + exception : la transaction REQUIRES_NEW rollback le claim,
 *       le paiement reste remboursable ;</li>
 *   <li>RELEASED / REFUNDED / FAILED / CANCELLED → no-op (jamais de refund post-versement).</li>
 * </ul>
 *
 * <p>{@code REQUIRES_NEW} : chaque paiement vit dans sa propre transaction — un échec dans un
 * traitement par lot (annulation de trajet) n'annule pas les remboursements déjà réussis.
 *
 * <p><b>Rail mobile money (tâche 17)</b> — {@code payment.getRail() == PAWAPAY} bascule sur un
 * second chemin, complètement séparé du chemin Stripe ci-dessus (aucune ligne du chemin Stripe
 * n'est modifiée par cette branche) : voir {@link #refundMobileMoney}. Les six listeners
 * appelants existants de {@link #processRefund} (annulations, litiges, rejets d'annonce) ne
 * changent pas — le dispatch sur le rail est entièrement interne à cette classe.
 */
@Component
public class RefundProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);

    /**
     * Ronde 1, point 5 : {@code admin_alerts.type} est {@code VARCHAR(60)}. Préfixe + UUID (36)
     * doit rester sous 60. Dédupliqué PAR PAIEMENT (pas un type global) — sinon la dédup
     * empêcherait à tort l'alerte d'un paiement B sous prétexte qu'un paiement A a déjà le même
     * problème non résolu. Voir {@code RefundProcessorMobileMoneyTest#noDepositAlertType_fitsInAdminAlertsTypeColumn}.
     */
    static final String NO_DEPOSIT_ALERT_PREFIX = "PAWAPAY_REFUND_NO_DEP_";

    /** Idem, pour le refus pawaPay du refund. Voir {@code ...#rejectedAlertType_fitsInAdminAlertsTypeColumn}. */
    static final String REJECTED_ALERT_PREFIX = "PAWAPAY_REFUND_REJECTED_";

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlert;
    private final PawapayOperationService pawapayOperations;
    private final PawapaySubmissionService pawapaySubmission;
    private final AdminAlertRepository alertRepository;

    /**
     * Transaction INDÉPENDANTE réservée à l'audit du remboursement mobile money (Ronde 1,
     * point 2) — même outil, même motif que {@code MobileMoneyPayoutInitiator#independentAuditTransaction} :
     * l'audit doit exister AVANT le rattachement (écriture ambiante, faillible) pour que la
     * trace d'un remboursement réellement soumis survive même si ce rattachement échoue et fait
     * rollback le claim.
     */
    private final TransactionTemplate independentAuditTransaction;

    public RefundProcessor(PaymentRepository paymentRepository,
                           AuditService auditService,
                           AdminAlertService adminAlert,
                           PawapayOperationService pawapayOperations,
                           PawapaySubmissionService pawapaySubmission,
                           AdminAlertRepository alertRepository,
                           PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.adminAlert = adminAlert;
        this.pawapayOperations = pawapayOperations;
        this.pawapaySubmission = pawapaySubmission;
        this.alertRepository = alertRepository;
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
        try {
            // Chez Yadony, la capture du PaymentIntent n'a lieu qu'à la livraison
            // (DeliveryConfirmedEvent). Un paiement ESCROW correspond donc, dans la
            // quasi-totalité des cas, à un PaymentIntent AUTORISÉ mais NON CAPTURÉ
            // (status=requires_capture). Stripe refuse Refund.create sur une charge
            // non capturée ("You must cancel the PaymentIntent ... instead of
            // refunding the Charge directly") → il faut annuler l'autorisation.
            // On ne rembourse (Refund) que si le PI a réellement été capturé (succeeded).
            PaymentIntent pi = PaymentIntent.retrieve(payment.getStripePaymentIntentId());
            if ("succeeded".equals(pi.getStatus())) {
                Refund.create(
                        RefundCreateParams.builder()
                                .setPaymentIntent(payment.getStripePaymentIntentId())
                                .build(),
                        RequestOptions.builder()
                                .setIdempotencyKey("refund-" + paymentId)
                                .build());
                stripeAction = "remboursement émis (PI capturé)";
            } else if (!"canceled".equals(pi.getStatus())) {
                // requires_capture (et autres états pré-capture) → libère le hold.
                pi.cancel(PaymentIntentCancelParams.builder()
                        .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                        .build());
                stripeAction = "autorisation annulée (PI non capturé)";
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

        auditService.log("PAYMENT", payment.getId(), auditAction, auditActor,
                enrich(auditPayload, payment));
        log.info("Escrow libéré pour PI {} — {} ({})",
                payment.getStripePaymentIntentId(), stripeAction, auditAction);
        return true;
    }

    /**
     * Rail mobile money (tâche 17). {@code PENDING} (jamais encaissé) → {@code CANCELLED}, sans
     * aucun appel pawaPay : si un deposit est encore en vol (par ex. {@code PROCESSING}) et
     * aboutit ensuite, c'est {@code MobileMoneyBidPaymentService#confirmEscrow} (tâche 14) qui
     * verra le paiement {@code CANCELLED} et remboursera lui-même ce deposit tardif —
     * volontairement PAS le rôle de cette méthode (voir le Javadoc de {@code confirmEscrow}).
     *
     * <p>{@code ESCROW} → claim atomique {@link PaymentRepository#markRefundedIfEscrow} — LA
     * MÊME primitive que le chemin Stripe ci-dessus, partagée entre les deux rails sur la même
     * colonne {@code status} : un double remboursement reste structurellement impossible quel
     * que soit le rail ou le nombre de chemins d'appel. Puis refund pawaPay du deposit
     * {@code COMPLETED} d'origine (trouvé via {@code findLatest}, jamais recalculé : le montant
     * remboursé est celui du deposit, {@link PawapayOperationEntity#getAmount()} — jamais
     * {@code payment.getAmount()}, égaux aujourd'hui mais sans garantie contractuelle). Un
     * refund déjà vivant ({@code findLive}) est rattaché tel quel, jamais resoumis (spec §7.1,
     * protégé structurellement par l'index unique partiel {@code uq_pawapay_ops_live_per_payment}).
     * L'absence de deposit {@code COMPLETED} ou un refus pawaPay (statut {@code SUBMIT_REJECTED})
     * remontent en exception : la transaction {@code REQUIRES_NEW} annule alors le claim, le
     * paiement redevient {@code ESCROW} et donc remboursable plus tard.
     *
     * <p><b>Ronde 1, point 1 (CRITIQUE)</b> : cette méthode et {@link #refundEscrowedMobileMoney}
     * ne font JAMAIS {@code payment.setStatus(...)} après un claim bulk — {@code enrich(...)}
     * n'utilise pas {@code status}, rien d'autre ne le lit non plus. Un tel setter rendrait
     * l'entité sale ; le mécanisme exact par lequel une écriture ultérieure sur {@code payments}
     * (ex. {@link #attachRefundId}) peut alors être écrasée au flush suivant dépend de l'ORDRE
     * d'exécution (vérifié empiriquement, voir
     * {@code PaymentRepositoryMobileMoneyTest#markRefundedIfEscrow_thenAttachRefundId_doesNotRevertStatus},
     * Ronde 1, et task-17-report.md) — ne JAMAIS dépendre de cet ordre : la seule garantie sûre
     * est de ne jamais salir l'entité gérée après le claim.
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
        int claimed = paymentRepository.markRefundedIfEscrow(paymentId);
        if (claimed == 0) {
            log.info("Paiement {} déjà sorti d'ESCROW — remboursement mobile money ignoré", paymentId);
            return false;
        }

        Optional<PawapayOperationEntity> completedDeposit = pawapayOperations
                .findLatest(paymentId, PawapayOperationKind.DEPOSIT)
                .filter(deposit -> deposit.getStatus() == PawapayOperationStatus.COMPLETED);
        if (completedDeposit.isEmpty()) {
            escalate(NO_DEPOSIT_ALERT_PREFIX, paymentId,
                    "Paiement " + paymentId + " en ESCROW sans deposit pawaPay COMPLETED : remboursement manuel requis",
                    Map.of("paymentId", paymentId.toString()),
                    "{\"paymentId\":\"" + paymentId + "\"}");
            throw new IllegalStateException("No completed pawaPay deposit for payment " + paymentId);
        }
        PawapayOperationEntity deposit = completedDeposit.get();

        // Ronde 1, point 9 : ce contrôle SUBMIT_REJECTED ne peut jamais se déclencher pour un
        // refund RÉCUPÉRÉ via findLive — PawapayOperationStatus.LIVE_OR_DONE (l'ensemble filtré
        // par findLive) exclut structurellement SUBMIT_REJECTED (rangé dans DEAD). Il n'est
        // donc jamais atteignable que pour un refund tout juste soumis par submitRefund
        // ci-dessous — pas un bug latent, juste une conséquence de la structure des deux
        // ensembles qui mérite ce commentaire pour le prochain lecteur.
        PawapayOperationEntity refund = pawapayOperations.findLive(paymentId, PawapayOperationKind.REFUND)
                .orElseGet(() -> pawapaySubmission.submitRefund(paymentId, deposit, deposit.getAmount()));
        if (refund.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            escalate(REJECTED_ALERT_PREFIX, paymentId,
                    "pawaPay a refusé le remboursement du paiement " + paymentId + " : " + refund.getFailureCode(),
                    Map.of("paymentId", paymentId.toString(), "operationId", refund.getId().toString(),
                            "failureCode", String.valueOf(refund.getFailureCode())),
                    "{\"paymentId\":\"" + paymentId + "\",\"operationId\":\"" + refund.getId() + "\"}");
            throw new IllegalStateException("pawaPay refund rejected: " + refund.getFailureCode());
        }

        // Ronde 1, point 2 : audit AVANT rattachement, dans sa propre transaction — motif repris
        // de MobileMoneyPayoutInitiator#release. L'audit part et commite indépendamment de la
        // transaction ambiante ; si attachRefundId (écriture ambiante, faillible) levait ensuite,
        // la trace du remboursement survivrait quand même au rollback du claim qu'il provoquerait.
        Map<String, Object> enriched = enrich(auditPayload, payment);
        enriched.put("refundOperationId", refund.getId().toString());
        independentAuditTransaction.executeWithoutResult(status ->
                auditService.log("PAYMENT", paymentId, auditAction, auditActor, enriched));
        // Ronde 1, point 1 : UPDATE ciblé, jamais un setter sur l'entité gérée après le claim
        // bulk ci-dessus (voir Javadoc de refundMobileMoney). Rien de faillible ne suit ce point.
        paymentRepository.attachRefundId(paymentId, refund.getId());
        log.info("Remboursement mobile money {} soumis pour le paiement {} ({})", refund.getId(), paymentId, auditAction);
        return true;
    }

    /**
     * Ronde 1, point 5 : dédupliquée par paiement, structure reprise à l'identique de
     * {@code MobileMoneyPayoutInitiator#escalateOrphan} / {@code PawapayReconciliationPoller#escalateUnknown}
     * — une alerte non résolue du même type est cherchée AVANT d'en créer une nouvelle et
     * d'appeler {@link AdminAlertService#raise}. Sans cette dédup, chaque nouvel appel à
     * {@code processRefund} sur le même paiement bloqué (retry, ou un second événement métier
     * visant le même paiement) reposterait une alerte Telegram identique — et
     * {@code PAWAPAY_REFUND_NO_DEPOSIT} demande elle-même « un remboursement manuel », dont la
     * reprise passera par la relance admin de la tâche 18.
     */
    private void escalate(String prefix, UUID paymentId, String detail, Map<String, Object> context, String payloadJson) {
        String type = prefix + paymentId;
        if (!alertRepository.findByTypeAndResolved(type, false).isEmpty()) {
            return;
        }
        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType(type);
        alert.setPayload(payloadJson);
        alert.setResolved(false);
        alertRepository.save(alert);
        adminAlert.raise(type, detail, context);
    }

    private Map<String, Object> enrich(Map<String, String> payload, PaymentEntity payment) {
        Map<String, Object> out = new HashMap<>(payload);
        out.put("piId", payment.getStripePaymentIntentId());
        out.put("amount", payment.getAmount().toPlainString());
        out.put("rail", payment.getRail().name());
        return out;
    }
}
