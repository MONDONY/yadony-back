package com.yadony.api.payments;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * n'est modifiée par cette branche) : voir {@link #refundMobileMoney}. Les sept listeners
 * appelants de {@link #processRefund} (annulations, litiges, rejets d'annonce, suppressions de
 * compte) ne changent pas — le dispatch sur le rail est entièrement interne à cette classe.
 */
@Component
public class RefundProcessor {

    private static final Logger log = LoggerFactory.getLogger(RefundProcessor.class);

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final AdminAlertService adminAlert;

    /**
     * Injection par champ, pas par constructeur : {@code RefundProcessorTest} (chemin Stripe,
     * antérieur à cette tâche) construit ce processor avec le constructeur à 3 paramètres —
     * ajouter les dépendances mobile money au constructeur l'aurait cassé sans aucun bénéfice
     * pour ces tests-là. Même convention que {@code MobileMoneyBidPaymentService#adminAlert}
     * (tâche 14).
     */
    @Autowired
    private PawapayOperationService pawapayOperations;

    @Autowired
    private PawapaySubmissionService pawapaySubmission;

    public RefundProcessor(PaymentRepository paymentRepository,
                           AuditService auditService,
                           AdminAlertService adminAlert) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.adminAlert = adminAlert;
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
     * remboursé est celui du deposit). Un refund déjà vivant ({@code findLive}) est rattaché tel
     * quel, jamais resoumis (spec §7.1, protégé structurellement par l'index unique partiel
     * {@code uq_pawapay_ops_live_per_payment}). L'absence de deposit {@code COMPLETED} ou un
     * refus pawaPay (statut {@code SUBMIT_REJECTED}) remontent en exception : la transaction
     * {@code REQUIRES_NEW} annule alors le claim, le paiement redevient {@code ESCROW} et donc
     * remboursable plus tard.
     *
     * <p><b>Piège hérité des tâches 14/16</b> : après le claim bulk, plus aucune écriture sur
     * l'entité {@code payment} gérée — le refund id est posé par l'UPDATE ciblé
     * {@link PaymentRepository#attachRefundId}, jamais par {@code payment.setPawapayRefundId(...)}
     * (voir le Javadoc détaillé d'{@link PaymentRepository#attachPayoutId}, même mécanisme exact).
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
                payment.setStatus(PaymentStatus.CANCELLED);
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
        payment.setStatus(PaymentStatus.REFUNDED);

        Optional<PawapayOperationEntity> completedDeposit = pawapayOperations
                .findLatest(paymentId, PawapayOperationKind.DEPOSIT)
                .filter(deposit -> deposit.getStatus() == PawapayOperationStatus.COMPLETED);
        if (completedDeposit.isEmpty()) {
            adminAlert.raise("PAWAPAY_REFUND_NO_DEPOSIT",
                    "Paiement " + paymentId + " en ESCROW sans deposit pawaPay COMPLETED : remboursement manuel requis",
                    Map.of("paymentId", paymentId.toString()));
            throw new IllegalStateException("No completed pawaPay deposit for payment " + paymentId);
        }
        PawapayOperationEntity deposit = completedDeposit.get();

        PawapayOperationEntity refund = pawapayOperations.findLive(paymentId, PawapayOperationKind.REFUND)
                .orElseGet(() -> pawapaySubmission.submitRefund(paymentId, deposit, payment.getAmount()));
        if (refund.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            adminAlert.raise("PAWAPAY_REFUND_REJECTED",
                    "pawaPay a refusé le remboursement du paiement " + paymentId + " : " + refund.getFailureCode(),
                    Map.of("paymentId", paymentId.toString(), "operationId", refund.getId().toString(),
                            "failureCode", String.valueOf(refund.getFailureCode())));
            throw new IllegalStateException("pawaPay refund rejected: " + refund.getFailureCode());
        }

        // Piège hérité des tâches 14/16 (voir Javadoc de refundMobileMoney) : UPDATE ciblé,
        // jamais payment.setPawapayRefundId(...) sur l'entité gérée après le claim bulk ci-dessus.
        paymentRepository.attachRefundId(paymentId, refund.getId());

        Map<String, Object> enriched = enrich(auditPayload, payment);
        enriched.put("refundOperationId", refund.getId().toString());
        auditService.log("PAYMENT", paymentId, auditAction, auditActor, enriched);
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
