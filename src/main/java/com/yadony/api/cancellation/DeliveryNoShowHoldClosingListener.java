package com.yadony.api.cancellation;

import com.yadony.api.common.AuditService;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Livraison confirmée pendant la garde d'un colis dont le destinataire était absent
 * (FLUTTER-E2, nouveau rendez-vous réussi ou nouveau destinataire) : le signalement est clos
 * (RESOLVED). L'argent suit le chemin normal de la livraison ; le passage « non réclamé » ne
 * sélectionne plus la ligne. Un signalement contesté (litige ouvert) n'est pas touché : l'admin
 * le tranche.
 */
@Component
public class DeliveryNoShowHoldClosingListener {

    private final CancellationRepository cancellationRepository;
    private final AuditService auditService;

    public DeliveryNoShowHoldClosingListener(CancellationRepository cancellationRepository,
                                             AuditService auditService) {
        this.cancellationRepository = cancellationRepository;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        cancellationRepository.findByBidIdAndScope(event.getBidId(), CancellationScope.DELIVERY)
                .filter(c -> DeliveryNoShowTypes.isRecipientNoShow(c.getReason()))
                .filter(c -> c.getHoldUntil() != null)
                .filter(c -> c.getNoShowStatus() == CancellationStatus.PENDING_CONFIRMATION
                        || c.getNoShowStatus() == CancellationStatus.CONFIRMED)
                .ifPresent(c -> {
                    CancellationStatus previous = c.getNoShowStatus();
                    c.setNoShowStatus(CancellationStatus.RESOLVED);
                    cancellationRepository.save(c);
                    auditService.log("CANCELLATION", c.getId(), "DELIVERY_NOSHOW_HOLD_CLOSED_BY_DELIVERY", null,
                            Map.of("bidId", event.getBidId().toString(),
                                    "previousStatus", previous.name(),
                                    "unclaimed", String.valueOf(c.getUnclaimedAt() != null)));
                });
    }
}
