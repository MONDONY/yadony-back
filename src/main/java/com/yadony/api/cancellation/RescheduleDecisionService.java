package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.ParcelReturnToSenderRequestedEvent;
import com.yadony.api.cancellation.events.TravelerHighCancellationEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.cancellation.events.TripRescheduleDecidedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TripCapacity;
import com.yadony.api.matching.TripRescheduleRules;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;
    private final CancellationRepository cancellationRepository;
    private final RematchService rematchService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Versement au dépôt déjà parti ? (port branché par Spring, paquet {@code payments}) ; repli prudent
     * {@link DepositPayoutPort#ASSUME_PAID} sans implémentation.
     */
    private DepositPayoutPort depositPayouts = DepositPayoutPort.ASSUME_PAID;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDepositPayoutPort(DepositPayoutPort depositPayouts) {
        this.depositPayouts = depositPayouts;
    }

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
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found",
                        "problem.not-found.user"));
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "bid-not-found",
                        "problem.not-found.bid"));
        if (!bid.getSenderId().equals(sender.getId())) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, "forbidden",
                    "problem.forbidden.not-sender-of-parcel");
        }
        if (bid.getPendingRescheduleId() == null || !TripRescheduleRules.DECISION_STATUSES.contains(bid.getStatus())) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "no-reschedule-pending",
                    "problem.no-reschedule-pending");
        }
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "announcement-not-found",
                        "problem.not-found.announcement"));
        if (!TripRescheduleRules.decisionOpen(bid, announcement)) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "reschedule-decision-closed",
                    "problem.reschedule-decision-closed");
        }

        // Colis payé au dépôt dont le transporteur a déjà reçu l'argent : se retirer annoncerait un remboursement
        // qui n'aura jamais lieu (RefundProcessor ne rembourse pas un paiement versé). 409 deposit-paid-no-refund,
        // rien n'est annulé ; le support ouvre un litige.
        if (decision == RescheduleDecision.WITHDRAW) {
            CancellationGuard.assertNoRefundAfterDepositPayout(bid, depositPayouts);
        }

        UUID rescheduleId = bid.getPendingRescheduleId();
        boolean parcelReturnRequired = false;
        if (decision == RescheduleDecision.KEEP) {
            bid.setPendingRescheduleId(null);
            bidRepository.save(bid);
            auditService.log("BID", bidId, "TRIP_RESCHEDULE_KEPT", sender.getId(),
                    Map.of("rescheduleId", rescheduleId.toString()));
        } else {
            parcelReturnRequired = withdraw(bid, announcement, sender, rescheduleId);
        }
        eventPublisher.publishEvent(new TripRescheduleDecidedEvent(
                bidId, sender.getId(), announcement.getTravelerId(), decision, sender.getFirstName(),
                parcelReturnRequired));
    }

    /** @return vrai si le colis était déjà remis et qu'une procédure de retour est ouverte. */
    private boolean withdraw(BidEntity bid, AnnouncementEntity announcement, UserEntity sender, UUID rescheduleId) {
        if (cancellationRepository.findByBidId(bid.getId()).isPresent()) {
            throw YadonyBusinessException.localized(HttpStatus.CONFLICT, "already-cancelled",
                    "problem.already-cancelled.parcel");
        }
        boolean handedOver = bid.getStatus() == BidStatus.HANDED_OVER;

        // Place rendue (kilos sauf KG_FREE, volume d'un bateau au m³), FULL → ACTIVE : règle unique TripCapacity.
        TripCapacity.release(announcement, bid);
        announcementRepository.save(announcement);

        if (handedOver) {
            // Le voyageur a déjà le colis : même procédure que l'annulation après remise
            // (CancellationService#cancelAfterHandover). Il le rend avant le délai contre le
            // code que détient l'expéditeur ; le contact reste ouvert pendant le retour
            // (ContactWindow#isReturnInProgress).
            ParcelReturn.open(bid, LocalDateTime.now());
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
        if (handedOver) {
            auditService.log("BID", bid.getId(), "RETURN_CODE_GENERATED", sender.getId(),
                    Map.of("returnDeadline", String.valueOf(bid.getReturnDeadline()),
                           "trigger", "TRIP_RESCHEDULE_WITHDRAWN"));
        }

        // Remboursement intégral par la matrice de TripCancelledEvent (par bid), comme
        // l'annulation après remise. Colis remis : il figure dans returnRequiredBidIds et
        // l'expéditeur reçoit PARCEL_RETURN_REQUIRED (code de retour dans le suivi) ; sinon
        // NotificationDispatcher ne relaie pas ce motif (l'expéditeur a agi lui-même).
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
                bidPaymentMethods, bidCommissionChargedVia, rematchInfo,
                handedOver ? Set.of(bid.getId()) : Set.of()));

        // Colis remis : le voyageur doit le rendre avant le délai et saisir le code de retour
        // (PARCEL_RETURN_TO_SENDER), comme quand l'expéditeur annule après la remise.
        if (handedOver && announcement.getTravelerId() != null) {
            eventPublisher.publishEvent(new ParcelReturnToSenderRequestedEvent(
                    bid.getId(), announcement.getTravelerId(), bid.getSenderId(),
                    bid.getReturnDeadline().toLocalDate()));
        }
        return handedOver;
    }
}
