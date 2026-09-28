package com.yadony.api.disputes;

import com.yadony.api.cancellation.events.NoShowAdminDecisionEvent;
import com.yadony.api.common.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Un administrateur a tranché une déclaration de no-show : le litige qu'elle avait ouvert
 * (contestation, ou « non contesté » à la livraison) n'a plus d'objet et est fermé.
 *
 * <p>Synchrone, dans la transaction de la décision : la déclaration et son litige changent
 * ensemble ou pas du tout. {@code resolution_type} vaut {@code NOSHOW_CONFIRMED} ou
 * {@code NOSHOW_REJECTED}. {@code resolution_note} reste vide : elle est montrée aux parties
 * (GET /disputes), alors que le motif de l'admin est interne. Pas de
 * {@code DisputeResolvedEvent} : les parties reçoivent déjà la notification de décision
 * no-show, une seconde notification « litige résolu » ferait doublon.
 */
@Component
public class NoShowDecisionDisputeListener {

    private static final String RESOLVED = "RESOLVED";

    private final DisputeRepository disputeRepository;
    private final AuditService auditService;

    public NoShowDecisionDisputeListener(DisputeRepository disputeRepository, AuditService auditService) {
        this.disputeRepository = disputeRepository;
        this.auditService = auditService;
    }

    @EventListener
    public void onDecision(NoShowAdminDecisionEvent event) {
        for (String type : event.disputeTypesToClose()) {
            disputeRepository.findByBidIdAndType(event.bidId(), type)
                    .filter(d -> !RESOLVED.equals(d.getStatus()))
                    .ifPresent(d -> close(d, event));
        }
    }

    private void close(DisputeEntity dispute, NoShowAdminDecisionEvent event) {
        dispute.setStatus(RESOLVED);
        dispute.setResolutionType("NOSHOW_" + event.decision().name());
        dispute.setResolvedAt(OffsetDateTime.now(ZoneOffset.UTC));
        disputeRepository.save(dispute);
        auditService.log("DISPUTE", dispute.getId(), "RESOLVED_BY_NOSHOW_DECISION", event.adminId(),
                Map.of("cancellationId", event.cancellationId().toString(),
                        "decision", event.decision().name()));
    }
}
