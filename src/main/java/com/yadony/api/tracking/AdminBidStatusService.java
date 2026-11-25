package com.yadony.api.tracking;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.dto.AdminBidStatusChangeRequest;
import com.yadony.api.tracking.dto.AdminBidStatusChangeResponse;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Changement de statut d'un colis par le super-admin (cas type : le voyageur a remis le colis
 * mais oublié de saisir le code, une trace écrite le prouve).
 *
 * <p>Passer à {@code COMPLETED} suit le chemin de la livraison confirmée par code : le code est
 * consommé, une étape d'arrivée est tracée, puis {@link DeliveryConfirmedEvent} déclenche tout le
 * reste (versement, notifications, statistiques, avis, fin de trajet). Les autres statuts ne
 * changent que le colis. Chaque changement est tracé dans l'audit avec le motif et l'admin.
 *
 * <p>Seuls les colis en cours de transport ({@code ACCEPTED} à {@code ARRIVED}) sont modifiables :
 * un colis annulé, refusé ou déjà livré porte de l'argent réglé ailleurs. Idempotent : un colis
 * déjà dans le statut demandé répond 200 {@code unchanged}, sans rien refaire.
 */
@Service
public class AdminBidStatusService {

    static final Set<BidStatus> CHANGEABLE_STATUSES = EnumSet.of(
            BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final TrackingEventRepository trackingEventRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AdminBidStatusService(BidRepository bidRepository,
                                 AnnouncementRepository announcementRepository,
                                 TrackingEventRepository trackingEventRepository,
                                 AuditService auditService,
                                 ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.trackingEventRepository = trackingEventRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    @CacheEvict(value = {"announcements-search", "traveler-bids-me", "bids-me"}, allEntries = true)
    public AdminBidStatusChangeResponse changeStatus(UUID bidId, UUID adminId,
                                                     AdminBidStatusChangeRequest request) {
        // Même verrou que la livraison par code et l'annulation : deux gestes simultanés se
        // sérialisent, le perdant relit le statut et refuse.
        bidRepository.lockForUpdate(bidId);
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "bid-not-found",
                        "Not Found", "Colis introuvable"));

        BidStatus previous = bid.getStatus();
        BidStatus target = request.status().bidStatus();
        if (previous == target) {
            return new AdminBidStatusChangeResponse(bidId, target.name(), previous.name(), true);
        }
        if (!CHANGEABLE_STATUSES.contains(previous)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "bid-status-not-changeable",
                    "Bid Status Not Changeable",
                    "Le statut de ce colis ne peut plus être modifié (statut actuel : " + previous + ").");
        }

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                        "Not Found", "Trajet introuvable"));

        // Littéraux, jamais un statut variable : AcceptanceFreezesPayoutTriggerTest interdit
        // qu'un setStatus(variable) puisse masquer un passage à ACCEPTED.
        switch (request.status()) {
            case HANDED_OVER -> bid.setStatus(BidStatus.HANDED_OVER);
            case IN_TRANSIT -> bid.setStatus(BidStatus.IN_TRANSIT);
            case ARRIVED -> bid.setStatus(BidStatus.ARRIVED);
            case COMPLETED -> bid.setStatus(BidStatus.COMPLETED);
        }
        if (target == BidStatus.COMPLETED) {
            bid.setConfirmationCode(null);
            bid.setConfirmationCodeExpiry(null);
            bid.setConfirmationCodeAttempts(0);
            bid.setConfirmationCodePublicEnabled(false);
        }
        bidRepository.save(bid);

        if (target == BidStatus.COMPLETED) {
            TrackingEventEntity event = new TrackingEventEntity();
            event.setBidId(bidId);
            event.setEventType(TrackingEventType.ARRIVEE);
            event.setScannedAt(LocalDateTime.now(ZoneOffset.UTC));
            trackingEventRepository.save(event);
            eventPublisher.publishEvent(
                    new DeliveryConfirmedEvent(bidId, bid.getSenderId(), announcement.getTravelerId()));
        }

        auditService.log("BID", bidId, "ADMIN_BID_STATUS_FORCED", adminId, Map.of(
                "previousStatus", previous.name(),
                "newStatus", target.name(),
                "reason", request.reason().trim()));

        return new AdminBidStatusChangeResponse(bidId, target.name(), previous.name(), false);
    }
}
