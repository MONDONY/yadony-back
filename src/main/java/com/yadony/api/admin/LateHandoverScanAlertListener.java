package com.yadony.api.admin;

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
 * Remise prouvée par un scan hors ligne arrivé après la décision « non remis »
 * ({@link LateHandoverScanEvent}) : alerte administrateur CRITIQUE, dédupliquée par colis, et audit
 * {@value #AUDIT_ACTION}. Écoute synchrone dans la transaction du scan, qui est ensuite annulée par
 * le 422 : l'alerte ({@link AdminAlertEscalator}, déjà en {@code REQUIRES_NEW}) et l'audit (ici en
 * {@code REQUIRES_NEW}) sont commités à part pour survivre au refus. Ne lève jamais : une panne
 * d'alerte ne doit pas changer la réponse du scan.
 */
@Component
public class LateHandoverScanAlertListener {

    private static final Logger log = LoggerFactory.getLogger(LateHandoverScanAlertListener.class);

    static final String ALERT_TYPE_PREFIX = "LATE_HANDOVER_SCAN_";
    static final String AUDIT_ACTION = "LATE_HANDOVER_SCAN_AFTER_DECISION";

    private final AdminAlertEscalator escalator;
    private final AuditService auditService;
    private final TransactionTemplate independentTransaction;

    public LateHandoverScanAlertListener(AdminAlertEscalator escalator, AuditService auditService,
                                         PlatformTransactionManager transactionManager) {
        this.escalator = escalator;
        this.auditService = auditService;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void onLateHandoverScan(LateHandoverScanEvent event) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("bidId", event.bidId().toString());
        context.put("bidStatus", event.bidStatus());
        context.put("rejectionReason", String.valueOf(event.rejectionReason()));
        context.put("decidedAt", event.decidedAt().toString());
        context.put("offlineTimestamp", event.offlineTimestamp().toString());
        if (event.scanMethod() != null) context.put("scanMethod", event.scanMethod());
        if (event.photoKey() != null) context.put("photoKey", event.photoKey());
        log.warn("Remise hors ligne arrivée après la décision : colis {} {} ({}), scanné à {}, décidé à {}",
                event.bidId(), event.bidStatus(), event.rejectionReason(), event.offlineTimestamp(), event.decidedAt());
        try {
            escalator.raiseOnce(ALERT_TYPE_PREFIX + event.bidId(), "CRITICAL",
                    "Colis " + event.bidId() + " scanné à la remise (hors ligne) avant d'être tranché " + event.bidStatus()
                            + " : il voyage peut-être alors que l'argent a suivi la décision. À vérifier.",
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
