package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import com.yadony.api.payments.currency.CurrencyAmount;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Garantit qu'un séquestre carte (modèle <i>separate charges and transfers</i>, non legacy) est
 * capturé sur le solde plateforme avant tout versement au voyageur.
 *
 * <p>Le modèle capture d'ordinaire dès le passage en séquestre (négociation,
 * {@link NegotiationCaptureListener}) ou à l'acceptation du colis ({@link BidAcceptedEventListener}).
 * Un paiement passé ESCROW sans que l'événement de capture parte (checkout d'avant #472) reste une
 * simple autorisation ({@code requires_capture}) : un {@code Transfer} créé dessus échoue, faute
 * de fonds sur le solde plateforme, et l'autorisation expire à J+7. La livraison, le colis non
 * réclamé et la libération forcée admin passent donc par ici juste avant leur claim.
 *
 * <p><b>Aucune transaction englobante</b> (PROD_CHECKLIST 9.23) : aucune connexion JDBC ni aucun
 * verrou de ligne n'est tenu pendant {@code PaymentIntent.retrieve} et {@code pi.capture}. La
 * garde « encore en séquestre » est relue juste avant la capture ; {@code captured_at}, le charge
 * id et l'audit sont écrits après la réponse de Stripe, dans une transaction courte, par des
 * UPDATE ciblés. Un échec de capture lève {@link EscrowCaptureException} (rien n'a été écrit), le
 * paiement reste ESCROW, et une alerte admin {@code ESCROW_CAPTURE_FAILED_<paymentId>} est levée
 * (persistée, Telegram, une fois tant qu'elle n'est pas résolue). Une capture faite chez Stripe
 * dont la réponse s'est perdue est retrouvée au passage suivant ({@code succeeded}).
 *
 * <p>L'appelant ne doit tenir aucune transaction : elle garderait sa connexion pendant l'appel.
 */
@Component
public class EscrowCaptureService {

    private static final Logger log = LoggerFactory.getLogger(EscrowCaptureService.class);

    static final String STATUS_REQUIRES_CAPTURE = "requires_capture";
    static final String STATUS_SUCCEEDED = "succeeded";
    static final String ALERT_PREFIX = "ESCROW_CAPTURE_FAILED_";

    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final AdminAlertEscalator alertEscalator;

    private final TransactionOperations transaction;

    @Autowired
    public EscrowCaptureService(PaymentRepository paymentRepository, AuditService auditService,
                                AdminAlertEscalator alertEscalator, PlatformTransactionManager transactionManager) {
        this(paymentRepository, auditService, alertEscalator, requiresNew(transactionManager));
    }

    EscrowCaptureService(PaymentRepository paymentRepository, AuditService auditService,
                         AdminAlertEscalator alertEscalator, TransactionOperations transaction) {
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.alertEscalator = alertEscalator;
        this.transaction = transaction;
    }

    static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Résultat d'une capture réussie ou déjà faite.
     *
     * @param chargeId     charge Stripe du PaymentIntent (pour {@code Transfer.sourceTransaction}),
     *                     éventuellement {@code null}
     * @param capturedNow  vrai si cet appel a capturé, faux si le PaymentIntent l'était déjà
     */
    public record Outcome(String chargeId, boolean capturedNow) {}

    /**
     * Capture le PaymentIntent du paiement s'il est encore en {@code requires_capture} ; ne fait
     * rien s'il est déjà {@code succeeded} (en complétant {@code captured_at} s'il manquait).
     * Idempotent : clé Stripe {@code capture-<paymentId>}, montant capturé = montant attendu.
     *
     * @param source chemin appelant, tracé dans l'audit et l'alerte (livraison, admin…)
     * @throws EscrowCaptureException si le paiement ne peut pas être capturé : rien n'est versé
     */
    public Outcome ensureCaptured(UUID paymentId, String source) {
        // Hors transaction (PROD_CHECKLIST 9.23) : lecture courte, appels Stripe sans connexion
        // JDBC ni verrou de ligne, puis écritures ciblées dans des transactions courtes.
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Paiement introuvable : " + paymentId));
        String piId = payment.getStripePaymentIntentId();

        PaymentIntent pi;
        try {
            pi = PaymentIntent.retrieve(piId,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge").build(), null);
        } catch (StripeException e) {
            throw failure(payment, source, null, null, "lecture du PaymentIntent impossible : " + e.getMessage(), e);
        }
        if (pi == null) {
            throw failure(payment, source, null, null, "PaymentIntent introuvable chez Stripe", null);
        }
        String status = pi.getStatus();
        Long captureBefore = captureBefore(pi);
        String chargeId = payment.getStripeChargeId() != null ? payment.getStripeChargeId() : pi.getLatestCharge();

        if (STATUS_SUCCEEDED.equals(status)) {
            // Déjà capturé (listener d'acceptation, capture manuelle, rejeu, capture précédente
            // dont la finalisation locale s'est perdue) : on complète seulement la trace locale.
            if (payment.getCapturedAt() == null || (payment.getStripeChargeId() == null && chargeId != null)) {
                transaction.executeWithoutResult(tx -> recordCaptured(paymentId, chargeId));
            }
            resolveCaptureFailedAlert(paymentId);
            return new Outcome(chargeId, false);
        }
        if (!STATUS_REQUIRES_CAPTURE.equals(status)) {
            // canceled (autorisation expirée ou annulée), requires_payment_method… : plus rien à capturer.
            throw failure(payment, source, status, captureBefore,
                    ("canceled".equals(status) || "requires_payment_method".equals(status))
                            ? "autorisation carte expirée ou annulée chez Stripe (PaymentIntent " + status
                                    + ") : plus rien à encaisser, rembourser l'expéditeur ou lui faire repayer"
                            : "PaymentIntent " + status + " : autorisation non capturable", null);
        }

        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(payment.getCurrency());
        long expected = CurrencyAmount.of(payment.getAmount(), currency).minor();
        Long capturable = pi.getAmountCapturable();
        if (pi.getAmount() == null || pi.getAmount() != expected || capturable == null || capturable < expected
                || !currency.code().equalsIgnoreCase(pi.getCurrency())) {
            // Jamais de capture partielle silencieuse : un écart de montant se tranche à la main.
            throw failure(payment, source, status, captureBefore,
                    "montant autorisé " + pi.getAmount() + " " + pi.getCurrency() + " (capturable " + capturable
                            + ") différent du montant attendu " + expected + " " + currency.code(), null);
        }

        // Garde relue en base juste avant la capture (règle 19), sans verrou tenu pendant l'appel :
        // un paiement sorti du séquestre (remboursé, versé, annulé) n'est jamais capturé. Une
        // annulation qui passerait entre cette lecture et la capture est arbitrée par Stripe, qui
        // sérialise les opérations d'un même PaymentIntent : soit l'annulation gagne et la capture
        // échoue (rien d'encaissé), soit la capture gagne et le remboursement, qui relit le
        // PaymentIntent, rembourse la charge capturée (RefundProcessor). Jamais de double capture :
        // un PaymentIntent ne se capture qu'une fois, et la clé capture-<paymentId> est stable.
        PaymentStatus current = paymentRepository.findStatusById(paymentId).orElse(null);
        if (current != PaymentStatus.ESCROW) {
            log.warn("Paiement {} plus en séquestre ({}) au moment de la capture (source {}) : capture abandonnée",
                    paymentId, current, source);
            throw new EscrowCaptureException("le paiement n'est plus en séquestre (" + current + ")", status, null);
        }
        PaymentIntent captured;
        try {
            captured = pi.capture(PaymentIntentCaptureParams.builder().setAmountToCapture(expected).build(),
                    RequestOptions.builder().setIdempotencyKey("capture-" + paymentId).build());
        } catch (StripeException e) {
            // Rien n'a été écrit avant l'appel : rien à annuler. Une capture faite chez Stripe malgré
            // une erreur réseau est retrouvée au prochain passage (PaymentIntent succeeded ci-dessus).
            throw failure(payment, source, status, captureBefore, "capture refusée par Stripe : " + e.getMessage(), e);
        }

        // Écritures ciblées (captured_at, charge id) : l'entité, chargée avant l'appel Stripe,
        // n'est jamais enregistrée (elle écraserait un statut, un remboursé ou un litige posé entre-temps).
        String capturedChargeId = captured != null && captured.getLatestCharge() != null
                ? captured.getLatestCharge() : chargeId;
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("piId", piId);
        audit.put("amountToCapture", expected);
        audit.put("currency", currency.code());
        audit.put("source", source);
        if (payment.getNegotiationThreadId() != null) {
            audit.put("threadId", payment.getNegotiationThreadId().toString());
        }
        transaction.executeWithoutResult(tx -> {
            recordCaptured(paymentId, capturedChargeId);
            auditService.log("PAYMENT", paymentId, "PAYMENT_CAPTURED_ON_PLATFORM", null, audit);
        });
        resolveCaptureFailedAlert(paymentId);
        log.info("PaymentIntent {} capturé sur le solde plateforme (paiement {}, source {})", piId, paymentId, source);
        return new Outcome(capturedChargeId, true);
    }

    /** Trace locale d'une capture confirmée par Stripe : {@code captured_at} et charge id s'ils manquent. */
    private void recordCaptured(UUID paymentId, String chargeId) {
        paymentRepository.markCapturedAt(paymentId, Instant.now());
        if (chargeId != null) {
            paymentRepository.setStripeChargeIdIfMissing(paymentId, chargeId);
        }
    }

    /**
     * Capture réussie (ou déjà faite) : l'alerte d'un échec précédent n'a plus d'objet. La
     * clore évite que {@code raiseOnce} ne masque un futur échec sur ce paiement.
     */
    private void resolveCaptureFailedAlert(UUID paymentId) {
        try {
            alertEscalator.resolveOpen(ALERT_PREFIX + paymentId);
        } catch (RuntimeException e) {
            log.warn("Alerte {}{} non close après capture : {}", ALERT_PREFIX, paymentId, e.getMessage());
        }
    }

    private EscrowCaptureException failure(PaymentEntity payment, String source, String piStatus,
                                           Long captureBefore, String reason, Exception cause) {
        UUID paymentId = payment.getId();
        log.error("Capture du séquestre impossible (paiement {}, PI {}, source {}) : {}",
                paymentId, payment.getStripePaymentIntentId(), source, reason, cause);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paymentId", String.valueOf(paymentId));
        context.put("piId", String.valueOf(payment.getStripePaymentIntentId()));
        context.put("piStatus", String.valueOf(piStatus));
        context.put("source", source);
        context.put("amount", String.valueOf(payment.getAmount()));
        context.put("currency", String.valueOf(payment.getCurrency()));
        if (captureBefore != null) {
            context.put("captureBefore", Instant.ofEpochSecond(captureBefore).toString());
        }
        try {
            alertEscalator.raiseOnce(ALERT_PREFIX + paymentId,
                    "Séquestre non capturé : le paiement " + paymentId + " (" + payment.getAmount() + " "
                            + payment.getCurrency() + ") n'a pas pu être capturé (" + reason + ")"
                            + (captureBefore != null ? ", capture possible jusqu'au "
                            + Instant.ofEpochSecond(captureBefore) : "")
                            + " ; aucun versement n'est parti, le paiement reste en séquestre",
                    context);
        } catch (RuntimeException alertFailure) {
            log.error("Alerte {} non levée pour le paiement {}", ALERT_PREFIX, paymentId, alertFailure);
        }
        return new EscrowCaptureException(reason, piStatus, cause);
    }

    /** Colis engagés : leur séquestre carte doit être capturé (mêmes statuts que la sonde INV-08). */
    public static final java.util.Set<com.yadony.api.matching.BidStatus> ENGAGED_BID_STATUSES = java.util.EnumSet.of(
            com.yadony.api.matching.BidStatus.ACCEPTED, com.yadony.api.matching.BidStatus.HANDED_OVER,
            com.yadony.api.matching.BidStatus.IN_TRANSIT, com.yadony.api.matching.BidStatus.ARRIVED,
            com.yadony.api.matching.BidStatus.COMPLETED);

    /**
     * Un séquestre carte autorisé ({@code requires_capture}) devrait-il déjà être capturé ? Oui
     * pour un paiement de négociation (capturé au passage en séquestre) et pour un colis classique
     * engagé (capturé à l'acceptation) ; non pour le modèle legacy (capture à la livraison) ni pour
     * un colis classique pas encore accepté (autorisation normale).
     *
     * @param bidStatus statut du colis classique ({@code bid_id}), {@code null} s'il est inconnu
     */
    public static boolean captureDue(PaymentEntity payment, com.yadony.api.matching.BidStatus bidStatus) {
        if (payment.isLegacyDestinationCharge()) {
            return false;
        }
        if (payment.getBidId() == null) {
            return payment.getNegotiationThreadId() != null;
        }
        return bidStatus != null && ENGAGED_BID_STATUSES.contains(bidStatus);
    }

    /** Date limite de capture de la carte ({@code capture_before}, epoch secondes), si Stripe la donne. */
    static Long captureBefore(PaymentIntent pi) {
        Charge charge = pi.getLatestChargeObject();
        if (charge == null || charge.getPaymentMethodDetails() == null
                || charge.getPaymentMethodDetails().getCard() == null) {
            return null;
        }
        return charge.getPaymentMethodDetails().getCard().getCaptureBefore();
    }

    /** Capture impossible : aucun versement ne doit partir, le paiement reste ESCROW. */
    public static class EscrowCaptureException extends RuntimeException {
        private final String piStatus;

        public EscrowCaptureException(String reason, String piStatus, Throwable cause) {
            super(reason, cause);
            this.piStatus = piStatus;
        }

        /** Statut Stripe du PaymentIntent au moment de l'échec ({@code null} s'il n'a pas pu être lu). */
        public String getPiStatus() {
            return piStatus;
        }
    }
}
