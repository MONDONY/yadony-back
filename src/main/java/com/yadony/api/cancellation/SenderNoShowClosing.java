package com.yadony.api.cancellation;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.HandoverScan;
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

    /**
     * Levée par le scan de remise, au nom du voyageur qui scanne. L'audit garde ce qui prouve la
     * remise : l'étape enregistrée, sa provenance, la clé de sa photo, l'heure hors ligne et la
     * position (coordonnées seules, jamais le libellé d'adresse).
     */
    void withdrawByHandover(CancellationEntity c, HandoverScan scan) {
        log.info("Déclaration d'absence {} levée par le scan de remise du colis {}", c.getId(), c.getBidId());
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("trackingEventId", String.valueOf(scan.trackingEventId()));
        putIfPresent(proof, "scanMethod", scan.scanMethod());
        putIfPresent(proof, "photoKey", scan.photoKey());
        putIfPresent(proof, "offlineTimestamp", scan.offlineTimestamp());
        putIfPresent(proof, "gpsLat", scan.gpsLat());
        putIfPresent(proof, "gpsLon", scan.gpsLon());
        resolve(c, AUDIT_WITHDRAWN_BY_HANDOVER, scan.travelerId(), null, proof);
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value.toString());
        }
    }

    /** Confirmation refusée, colis déjà remis ; {@code actorId} nul pour l'échéance. */
    void closeBecauseParcelHandedOver(CancellationEntity c, BidStatus bidStatus, UUID actorId) {
        log.warn("Absence de l'expéditeur non confirmée : le colis {} est déjà {} (déclaration {} close,"
                + " aucun remboursement)", c.getBidId(), bidStatus, c.getId());
        resolve(c, AUDIT_CLOSED_PARCEL_HANDED_OVER, actorId, bidStatus, Map.of());
    }

    private void resolve(CancellationEntity c, String action, UUID actorId, BidStatus bidStatus,
                         Map<String, Object> extra) {
        CancellationStatus previous = c.getNoShowStatus();
        c.setNoShowStatus(CancellationStatus.RESOLVED);
        cancellations.save(c);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bidId", c.getBidId().toString());
        if (bidStatus != null) {
            payload.put("bidStatus", bidStatus.name());
        }
        payload.putAll(extra);
        payload.put("previousStatus", previous.name());
        auditService.log("CANCELLATION", c.getId(), action, actorId, payload);
    }
}
