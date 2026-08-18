package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.events.BidNegotiationExpiredEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Expiration d'UN fil, dans sa propre transaction ({@code REQUIRES_NEW}).
 *
 * <p>Séparé de {@link BidNegotiationExpiryScheduler} pour que le proxy transactionnel
 * s'applique réellement : une auto-invocation depuis le scheduler le contournerait,
 * et un seul conflit d'écriture annulerait tout le lot.
 *
 * <p>Le statut est relu et revérifié : un fil accepté ou rejeté entre le balayage et
 * l'écriture ne doit pas être écrasé. C'est ce qui rend l'opération idempotente, deux
 * passages successifs ne produisant qu'une seule transition.
 */
@Component
public class BidNegotiationExpiryRunner {

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public BidNegotiationExpiryRunner(BidRepository bidRepository,
                                      AnnouncementRepository announcementRepository,
                                      ApplicationEventPublisher eventPublisher,
                                      AuditService auditService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(UUID bidId, String reason) {
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null || bid.getStatus() != BidStatus.NEGOTIATING) {
            return;
        }

        // NEGOTIATION_CLOSED et non EXPIRED, pour la même raison qu'un refus ou un
        // retrait : EXPIRED est un statut de COLIS. Un fil périmé y basculait, sortait
        // des statuts de négociation et réapparaissait dans « Mes envois » sous les
        // traits d'une demande de colis expirée, alors qu'aucun colis n'a jamais existé.
        bid.setStatus(BidStatus.NEGOTIATION_CLOSED);
        bidRepository.save(bid);

        auditService.log("BID", bidId, "BID_NEGOTIATION_EXPIRED", null,
                Map.of("source", "SYSTEM", "reason", reason));

        // Sans le trajet, la contrepartie est inconnue : on éteint quand même le fil,
        // mais il n'y a personne de nommé à prévenir.
        announcementRepository.findById(bid.getAnnouncementId()).ifPresent(announcement ->
                eventPublisher.publishEvent(new BidNegotiationExpiredEvent(
                        bidId, announcement.getId(), bid.getSenderId(),
                        announcement.getTravelerId(), reason)));
    }
}
