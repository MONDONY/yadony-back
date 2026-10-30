package com.yadony.api.cancellation;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Clôt une déclaration « expéditeur absent » (HANDOVER, SENDER_NO_SHOW) en {@code RESOLVED}, sans
 * {@code CancellationConfirmedEvent} : ni annulation, ni remboursement, ni pénalité. C'est l'état
 * d'une déclaration rejetée (« le colis continue ») ; les échéances ne lisent que
 * {@code PENDING_CONFIRMATION}, la porte de remise ne bloque plus. Source unique de cette clôture
 * et de son audit, pour les deux raisons de l'appliquer :
 * <ul>
 *   <li>{@link #withdrawByHandover} : le voyageur scanne la remise alors que la déclaration attend
 *       encore la réponse de l'expéditeur, qui est donc venu ({@link SenderNoShowHandoverGate}) ;</li>
 *   <li>{@link #closeBecauseParcelHandedOver} : la confirmation (échéance, expéditeur) arrive sur un
 *       colis déjà remis ({@link BidStatus#SENDER_SHOWED_UP}), déclaration ouverte avant la porte.</li>
 * </ul>
 * L'appelant tient le verrou du colis. Pas un bean : chaque appelant du package la construit avec
 * ses dépendances, sans changer les constructeurs existants.
 */
final class SenderNoShowClosing {

    private static final Logger log = LoggerFactory.getLogger(SenderNoShowClosing.class);

    static final String AUDIT_WITHDRAWN_BY_HANDOVER = "SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER";
    static final String AUDIT_CLOSED_PARCEL_HANDED_OVER = "SENDER_NO_SHOW_CLOSED_PARCEL_HANDED_OVER";

    private final CancellationRepository cancellations;
    private final AuditService auditService;

    SenderNoShowClosing(CancellationRepository cancellations, AuditService auditService) {
        this.cancellations = cancellations;
        this.auditService = auditService;
    }

    /** Levée par le scan de remise ; {@code travelerId} : le voyageur qui scanne. */
    void withdrawByHandover(CancellationEntity c, UUID travelerId) {
        log.info("Déclaration d'absence {} levée par le scan de remise du colis {}", c.getId(), c.getBidId());
        resolve(c, AUDIT_WITHDRAWN_BY_HANDOVER, travelerId, null);
    }

    /** Confirmation refusée, colis déjà remis ; {@code actorId} nul pour l'échéance. */
    void closeBecauseParcelHandedOver(CancellationEntity c, BidStatus bidStatus, UUID actorId) {
        log.warn("Absence de l'expéditeur non confirmée : le colis {} est déjà {} (déclaration {} close,"
                + " aucun remboursement)", c.getBidId(), bidStatus, c.getId());
        resolve(c, AUDIT_CLOSED_PARCEL_HANDED_OVER, actorId, bidStatus);
    }

    private void resolve(CancellationEntity c, String action, UUID actorId, BidStatus bidStatus) {
        CancellationStatus previous = c.getNoShowStatus();
        c.setNoShowStatus(CancellationStatus.RESOLVED);
        cancellations.save(c);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", c.getBidId().toString());
        if (bidStatus != null) {
            payload.put("bidStatus", bidStatus.name());
        }
        payload.put("previousStatus", previous.name());
        auditService.log("CANCELLATION", c.getId(), action, actorId, payload);
    }
}
