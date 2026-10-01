package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.TravelerHighCancellationEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.cancellation.events.TripRescheduleDecidedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.matching.TripRescheduleRules;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Réponse de l'expéditeur au report du trajet de son colis (cf.
 * {@code matching.TripRescheduleService}). Garder : le colis reste sur la nouvelle date.
 * Se retirer : bid annulé, kilos rendus au trajet, remboursement intégral et autres
 * trajets proposés ; le voyageur, qui a changé ses dates, en porte l'annulation dans sa
 * réputation. Un colis déjà remis repart vers l'expéditeur avec un code de retour, comme
 * une annulation après remise.
 */
@Service
public class RescheduleDecisionService {

    private static final SecureRandom RETURN_CODE_RANDOM = new SecureRandom();

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final CancellationRepository cancellationRepository;
    private final RematchService rematchService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public RescheduleDecisionService(BidRepository bidRepository,
                                     AnnouncementRepository announcementRepository,
                                     UserRepository userRepository,
                                     CancellationRepository cancellationRepository,
                                     RematchService rematchService,
                                     AuditService auditService,
                                     ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
        this.cancellationRepository = cancellationRepository;
        this.rematchService = rematchService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void decide(String firebaseUid, UUID bidId, RescheduleDecision decision) {
        UserEntity sender = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found",
                        "Not Found", "Utilisateur introuvable"));
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "bid-not-found",
                        "Not Found", "Bid introuvable"));
        if (!bid.getSenderId().equals(sender.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de ce colis.");
        }
        if (bid.getPendingRescheduleId() == null || !TripRescheduleRules.DECISION_STATUSES.contains(bid.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "no-reschedule-pending",
                    "No Reschedule Pending", "Aucun report de trajet n'attend votre réponse.");
        }
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                        "Not Found", "Annonce introuvable"));
        if (!TripRescheduleRules.decisionOpen(bid, announcement)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "reschedule-decision-closed",
                    "Decision Closed",
                    "Le délai pour répondre au report est dépassé : votre colis reste sur le trajet.");
        }

        UUID rescheduleId = bid.getPendingRescheduleId();
        if (decision == RescheduleDecision.KEEP) {
            bid.setPendingRescheduleId(null);
            bidRepository.save(bid);
            auditService.log("BID", bidId, "TRIP_RESCHEDULE_KEPT", sender.getId(),
                    Map.of("rescheduleId", rescheduleId.toString()));
        } else {
            withdraw(bid, announcement, sender, rescheduleId);
        }
        eventPublisher.publishEvent(new TripRescheduleDecidedEvent(
                bidId, sender.getId(), announcement.getTravelerId(), decision));
    }

    private void withdraw(BidEntity bid, AnnouncementEntity announcement, UserEntity sender, UUID rescheduleId) {
        if (cancellationRepository.findByBidId(bid.getId()).isPresent()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "already-cancelled",
                    "Already Cancelled", "Une annulation existe déjà pour ce colis.");
        }
        boolean handedOver = bid.getStatus() == BidStatus.HANDED_OVER;

        boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
        if (!isKgFree && bid.getWeightKg() != null) {
            announcement.setAvailableKg(announcement.getAvailableKg().add(bid.getWeightKg()));
        }
        if (!isKgFree && announcement.getStatus() == AnnouncementStatus.FULL) {
            announcement.setStatus(AnnouncementStatus.ACTIVE);
        }
        announcementRepository.save(announcement);

        if (handedOver) {
            // Le voyageur a déjà le colis : il le rend contre le code que détient l'expéditeur.
            LocalDateTime now = LocalDateTime.now();
            bid.setReturnCode(String.format("%06d", RETURN_CODE_RANDOM.nextInt(1_000_000)));
            bid.setReturnCodeExpiry(now.plusDays(3));
            bid.setReturnCodeAttempts(0);
            bid.setReturnDeadline(now.plusDays(3));
        }
        bid.setStatus(BidStatus.CANCELLED);
        bid.setPendingRescheduleId(null);
        bidRepository.save(bid);

        CancellationEntity cancellation = new CancellationEntity();
        cancellation.setBidId(bid.getId());
        cancellation.setCancelledBy(sender.getId());
        cancellation.setReason(CancellationReason.TRIP_RESCHEDULE_WITHDRAWN.name());
        cancellation = cancellationRepository.save(cancellation);

        // Le report vient du voyageur : l'annulation compte dans sa réputation, pas dans
        // celle de l'expéditeur.
        UserEntity traveler = userRepository.findById(announcement.getTravelerId()).orElse(null);
        if (traveler != null) {
            traveler.setCancellationCount(traveler.getCancellationCount() + 1);
            userRepository.save(traveler);
            if (traveler.getCancellationCount() >= 3) {
                auditService.log("USER", traveler.getId(), "HIGH_CANCELLATION_ALERT", traveler.getId(),
                        Map.of("cancellationCount", String.valueOf(traveler.getCancellationCount()),
                               "triggeringAnnouncementId", announcement.getId().toString()));
                eventPublisher.publishEvent(new TravelerHighCancellationEvent(
                        traveler.getId(), traveler.getCancellationCount(), announcement.getId()));
            }
        }

        Map<UUID, RematchService.RematchInfo> rematch = rematchService.generateForCancellations(
                announcement, List.of(bid), List.of(cancellation));

        auditService.log("BID", bid.getId(), "TRIP_RESCHEDULE_WITHDRAWN", sender.getId(),
                Map.of("rescheduleId", rescheduleId.toString(),
                       "handedOver", String.valueOf(handedOver),
                       "paymentMethod", bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE"));

        // Remboursement intégral par la matrice de TripCancelledEvent (par bid), comme
        // l'annulation après remise. NotificationDispatcher ne relaie pas ce motif :
        // TripRescheduleDecidedEvent porte la notification du voyageur.
        Map<UUID, String> bidPaymentMethods = new HashMap<>();
        Map<UUID, String> bidCommissionChargedVia = new HashMap<>();
        bidPaymentMethods.put(bid.getId(), bid.getPaymentMethod() != null ? bid.getPaymentMethod().name() : "STRIPE");
        if (bid.getCommissionChargedVia() != null) {
            bidCommissionChargedVia.put(bid.getId(), bid.getCommissionChargedVia().name());
        }
        Map<UUID, TripCancelledEvent.RematchBySenderInfo> rematchInfo = new HashMap<>();
        rematch.forEach((senderId, info) -> rematchInfo.put(senderId,
                new TripCancelledEvent.RematchBySenderInfo(info.cancellationId(), info.suggestionCount())));
        eventPublisher.publishEvent(new TripCancelledEvent(
                announcement.getId(), announcement.getTravelerId(), List.of(bid.getSenderId()),
                CancellationReason.TRIP_RESCHEDULE_WITHDRAWN.name(), List.of(bid.getId()),
                bidPaymentMethods, bidCommissionChargedVia, rematchInfo));
    }
}
