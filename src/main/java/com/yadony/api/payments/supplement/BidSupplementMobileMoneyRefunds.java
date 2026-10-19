package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Remboursement intégral d'un supplément mobile money à l'expéditeur (plan §4.3 « Remboursement ») :
 * colis terminé sans livraison, paiement principal remboursé par l'administration, ou dépôt abouti
 * après la clôture de la demande. Refund pawaPay du DÉPÔT du supplément, pour son montant entier
 * ({@code related_operation_id} = le dépôt), comme {@code RefundProcessor.refundEscrowedMobileMoney}.
 *
 * <p>Réservation en transaction courte ({@code PAID → REFUND_PENDING}), soumission hors
 * transaction, puis {@code REFUNDED} à la confirmation ({@link BidSupplementOutcomeListener}). Refus ou
 * échec : le supplément reste à rembourser, alerte admin, le filet réessaie, au plus
 * {@code mm-max-attempts} opérations mortes ; au-delà, ou opérateur sans remboursement, plus aucune
 * soumission automatique (intervention manuelle). Jamais de remboursement après un versement.
 */
@Component
public class BidSupplementMobileMoneyRefunds {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementMobileMoneyRefunds.class);

    private final BidSupplementMobileMoneyLedger ledger;
    private final PawapayOperationService operations;
    private final PawapaySubmissionService submission;
    private final PawapayClient client;
    private final AdminAlertEscalator alerts;
    private final BidSupplementProperties properties;

    public BidSupplementMobileMoneyRefunds(BidSupplementMobileMoneyLedger ledger, PawapayOperationService operations,
                                           PawapaySubmissionService submission, PawapayClient client,
                                           AdminAlertEscalator alerts, BidSupplementProperties properties) {
        this.ledger = ledger;
        this.operations = operations;
        this.submission = submission;
        this.client = client;
        this.alerts = alerts;
        this.properties = properties;
    }

    /** @return vrai si un remboursement a été soumis (ou constaté abouti) par cet appel. */
    public boolean refund(UUID supplementId) {
        BidSupplementMobileMoneyLedger.RefundClaim decision = ledger.beginRefund(supplementId);
        switch (decision.decision()) {
            case NOTHING -> {
                return false;
            }
            case AFTER_PAYOUT -> {
                alerts.raiseOnce("SUPP_REFUND_PAID_OUT_" + supplementId,
                        "Supplément " + supplementId + " déjà versé au voyageur alors que le colis "
                                + decision.claim().bidId() + " n'est pas livré : remboursement manuel à décider",
                        context(decision.claim(), Map.of()));
                return false;
            }
            default -> {
                return execute(decision.claim());
            }
        }
    }

    private boolean execute(BidSupplementMobileMoneyLedger.Claim claim) {
        UUID id = claim.supplementId();
        Optional<PawapayOperationEntity> payout = operations.findLiveForSupplement(id, PawapayOperationKind.PAYOUT);
        if (payout.isPresent()) {
            alerts.raiseOnce("SUPP_MM_PAYOUT_EXISTS_" + id,
                    "Supplément " + id + " à rembourser alors qu'un versement pawaPay existe (opération "
                            + payout.get().getId() + ", " + payout.get().getStatus() + ") : vérification manuelle",
                    context(claim, Map.of("operationId", payout.get().getId().toString())));
            return false;
        }
        Optional<PawapayOperationEntity> existing = operations.findLiveForSupplement(id, PawapayOperationKind.REFUND);
        if (existing.isPresent()) {
            // Remboursement déjà soumis : abouti, on l'enregistre (événement perdu) ; en cours, on attend.
            if (existing.get().getStatus() == PawapayOperationStatus.COMPLETED) {
                return ledger.completeRefund(id, existing.get().getId(), "reconcile");
            }
            return false;
        }
        Optional<PawapayOperationEntity> deposit = operations.findLiveForSupplement(id, PawapayOperationKind.DEPOSIT)
                .filter(op -> op.getStatus() == PawapayOperationStatus.COMPLETED);
        if (deposit.isEmpty()) {
            alerts.raiseOnce("SUPP_MM_NO_DEPOSIT_" + id,
                    "Supplément " + id + " à rembourser sans dépôt pawaPay abouti : remboursement manuel requis",
                    context(claim, Map.of()));
            return false;
        }
        PawapayOperationEntity depositOp = deposit.get();
        long dead = operations.countDeadForSupplement(id, PawapayOperationKind.REFUND);
        if (dead >= properties.getMmMaxAttempts()) {
            // Échec définitif probable (dépôt trop ancien, déjà remboursé, solde) : plus de soumission.
            alerts.raiseOnce("SUPP_MM_REFUND_MANUAL_" + id,
                    "Remboursement mobile money du supplément " + id + " : " + dead + " échecs, plus de nouvelle "
                            + "tentative automatique, intervention manuelle requise",
                    context(claim, Map.of("deadOperations", dead)));
            return false;
        }
        if (Boolean.FALSE.equals(supportsRefund(depositOp))) {
            // v1 : pas de repli par versement vers le numéro payeur (noté pour une v2).
            alerts.raiseOnce("SUPP_MM_NO_REFUND_" + id,
                    "Supplément " + id + " : l'opérateur " + depositOp.getProvider()
                            + " ne rembourse pas les dépôts, remboursement manuel requis",
                    context(claim, Map.of("provider", String.valueOf(depositOp.getProvider()))));
            return false;
        }
        PawapayOperationEntity op;
        try {
            op = submission.submitSupplementRefund(id, claim.senderId(), depositOp, depositOp.getAmount());
        } catch (RuntimeException e) {
            if (operations.findLiveForSupplement(id, PawapayOperationKind.REFUND).isPresent()) {
                log.warn("Remboursement du supplément {} sans réponse pawaPay, laissé au poller ({})", id, e.toString());
                return true;
            }
            return failed(claim, String.valueOf(e.getMessage()), e);
        }
        if (op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED) {
            return failed(claim, String.valueOf(op.getFailureCode()), null);
        }
        ledger.auditOnly(id, "BID_SUPPLEMENT_REFUND_SUBMITTED", Map.of("operationId", op.getId().toString(),
                "depositId", depositOp.getId().toString()));
        log.info("Remboursement mobile money {} soumis pour le supplément {}", op.getId(), id);
        return true;
    }

    private boolean failed(BidSupplementMobileMoneyLedger.Claim claim, String error, Exception cause) {
        UUID id = claim.supplementId();
        log.error("Remboursement mobile money du supplément {} en échec : {}", id, error, cause);
        ledger.auditOnly(id, "BID_SUPPLEMENT_REFUND_FAILED", Map.of("error", error));
        alerts.raiseOnce("SUPP_REFUND_FAILED_" + id,
                "Remboursement mobile money du supplément " + id + " en échec : " + error,
                context(claim, Map.of("error", error)));
        return false;
    }

    /** Vide (nul) si la configuration active pawaPay est illisible : on tente alors le remboursement. */
    private Boolean supportsRefund(PawapayOperationEntity deposit) {
        try {
            PawapayProviderConfig config = client.activeConfiguration().get(deposit.getProvider());
            return config != null && config.supportsRefund();
        } catch (RuntimeException e) {
            log.warn("Supplément : configuration active pawaPay indisponible ({})", e.toString());
            return null;
        }
    }

    private static Map<String, Object> context(BidSupplementMobileMoneyLedger.Claim claim, Map<String, Object> extra) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("supplementId", claim.supplementId().toString());
        context.put("bidId", claim.bidId().toString());
        context.putAll(extra);
        return context;
    }
}
