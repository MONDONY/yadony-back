package com.yadony.api.admin;

import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.tracking.events.LateHandoverScanEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scan de remise hors ligne arrivé après la décision « non remis » ({@link LateHandoverScanEvent}) :
 * alerte administrateur CRITIQUE et audit {@value #AUDIT_ACTION}, présentés comme une DÉCLARATION
 * DU CLIENT, NON VÉRIFIÉE ({@code clientDeclared=true}, {@code verified=false}, plus
 * {@code proofValidated} et {@code proofMethod} : seul un numéro de suivi juste est une preuve
 * vérifiée), jamais comme un fait établi.
 *
 * <p>Pas de rafale : une seule alerte et un seul audit par colis. Un colis n'a qu'une décision
 * terminale (CANCELLED ou NO_SHOW, sans retour), donc « par colis » vaut « par colis et par
 * décision ». L'audit, immuable, fait foi : s'il existe déjà pour ce colis, rien n'est relevé, même
 * après le traitement de l'alerte. Les rejeux du même colis sont sérialisés par le verrou du colis
 * que tient le scan ; l'alerte reste en plus dédupliquée par {@link AdminAlertEscalator}.
 *
 * <p>Écoute synchrone dans la transaction du scan, qui est ensuite annulée par le 422 : l'alerte
 * (déjà en {@code REQUIRES_NEW}) et l'audit (ici en {@code REQUIRES_NEW}) sont commités à part pour
 * survivre au refus. Aucune écriture métier (colis, déclaration, paiement). Ne lève jamais : une
 * panne d'alerte ne doit pas changer la réponse du scan.
 */
@Component
public class LateHandoverScanAlertListener {

    private static final Logger log = LoggerFactory.getLogger(LateHandoverScanAlertListener.class);

    static final String ALERT_TYPE_PREFIX = "LATE_HANDOVER_SCAN_";
    static final String AUDIT_ACTION = "LATE_HANDOVER_SCAN_AFTER_DECISION";

    private final AdminAlertEscalator escalator;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;
    private final TransactionTemplate independentTransaction;

    public LateHandoverScanAlertListener(AdminAlertEscalator escalator, AuditService auditService,
                                         AuditLogRepository auditLogRepository,
                                         PlatformTransactionManager transactionManager) {
        this.escalator = escalator;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void onLateHandoverScan(LateHandoverScanEvent event) {
        try {
            Boolean alreadyReported = independentTransaction.execute(status ->
                    auditLogRepository.findLastActionAt("BID", event.bidId(), AUDIT_ACTION).isPresent());
            if (Boolean.TRUE.equals(alreadyReported)) {
                log.info("Remise hors ligne tardive du colis {} déjà signalée : rien de plus", event.bidId());
                return;
            }
        } catch (RuntimeException e) {
            log.error("Signalement de remise tardive non vérifiable pour le colis {}", event.bidId(), e);
            return;
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("clientDeclared", true);
        context.put("verified", false);
        context.put("proofValidated", event.proofValidated());
        context.put("proofMethod", event.proofMethod());
        context.put("bidId", event.bidId().toString());
        context.put("bidStatus", event.bidStatus());
        context.put("rejectionReason", String.valueOf(event.rejectionReason()));
        context.put("decidedAt", event.decidedAt().toString());
        context.put("offlineTimestamp", event.offlineTimestamp().toString());
        if (event.scanMethod() != null) context.put("scanMethod", event.scanMethod());
        if (event.photoKey() != null) context.put("photoKey", event.photoKey());
        log.warn("Remise hors ligne DÉCLARÉE après la décision (non vérifiée, preuve {}) : colis {} {} ({}),"
                        + " scan déclaré à {}, décidé à {}", event.proofMethod(), event.bidId(), event.bidStatus(),
                event.rejectionReason(), event.offlineTimestamp(), event.decidedAt());
        try {
            escalator.raiseOnce(ALERT_TYPE_PREFIX + event.bidId(), "CRITICAL",
                    "DÉCLARÉ PAR LE CLIENT, NON VÉRIFIÉ : le voyageur dit avoir scanné la remise du colis "
                            + event.bidId() + " hors ligne le " + event.offlineTimestamp() + ", avant qu'il soit tranché "
                            + event.bidStatus() + " le " + event.decidedAt() + ". Preuve vérifiée : "
                            + (event.proofValidated() ? "numéro de suivi" : "aucune")
                            + ". Si c'est vrai, le colis voyage alors que l'argent a suivi la décision : à vérifier.",
                    context);
        } catch (RuntimeException e) {
            log.error("Alerte de remise tardive non levée pour le colis {}", event.bidId(), e);
        }
        try {
            independentTransaction.executeWithoutResult(status ->
                    auditService.log("BID", event.bidId(), AUDIT_ACTION, event.travelerId(), context));
        } catch (RuntimeException e) {
            log.error("Audit de remise tardive non écrit pour le colis {}", event.bidId(), e);
        }
    }
}
