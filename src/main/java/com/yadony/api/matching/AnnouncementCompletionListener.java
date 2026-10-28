package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.events.AnnouncementCompletedEvent;
import com.yadony.api.matching.events.HandoverExpiredEvent;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.TripActivityChangedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Transitions an {@link AnnouncementEntity} to {@link AnnouncementStatus#COMPLETED} when all
 * in-flight bids are resolved. Triggers on three events:
 * <ul>
 *   <li>{@link DeliveryConfirmedEvent} — last ACCEPTED bid delivered via QR scan</li>
 *   <li>{@link VoyageurNoShowEvent} — traveler no-showed, bid → NO_SHOW</li>
 *   <li>{@link ParcelRefusedEvent} — traveler refused parcel, bid → PARCEL_REFUSED</li>
 *   <li>{@link HandoverExpiredEvent} — cash handover never confirmed nor reported, bid → CANCELLED</li>
 * </ul>
 *
 * <p>Rule: a trip is done when no bid on the announcement remains in flight
 * ({@link BidStatus#IN_FLIGHT}) AND the trip actually carried something or actually left:
 * <ul>
 *   <li>at least one parcel delivered ({@link BidStatus#COMPLETED}) → COMPLETED ;</li>
 *   <li>nothing delivered, trip {@link AnnouncementStatus#IN_PROGRESS} (departure passed, or
 *       underway early through a transit scan) → COMPLETED, same rule as the departure
 *       transition ({@code AnnouncementService#applyInProgressTransition}, trigger
 *       {@code DEPARTURE_NO_ACCEPTED_BIDS}) ;</li>
 *   <li>nothing delivered, trip still ACTIVE/FULL → left open (YADONY-FLUTTER-PROD-15) : a
 *       parcel refused, a cash handover expired or a no-show before departure must not close
 *       the trip, which stays bookable. The freed capacity is given back (and FULL → ACTIVE) by
 *       the transaction that ended the parcel ({@code BidService#refuseParcel},
 *       {@code NoShowService#expireUnconfirmedCashHandover}), never here. If departure time
 *       has passed meanwhile, the departure scheduler closes the trip and also expires the
 *       pending requests, which completing it here would have skipped.</li>
 * </ul>
 * PENDING/EXPIRED are not counted (pre-departure commitments, not in-flight).
 *
 * <p>Idempotent: COMPLETED/CANCELLED announcements are silently skipped.
 * Uses AFTER_COMMIT to prevent phantom transitions on rollback.
 */
@Component
public class AnnouncementCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementCompletionListener.class);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AnnouncementCompletionListener(BidRepository bidRepository,
                                          AnnouncementRepository announcementRepository,
                                          AuditService auditService,
                                          ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        Optional<BidEntity> bidOpt = bidRepository.findById(event.getBidId());
        if (bidOpt.isEmpty()) {
            log.warn("DeliveryConfirmedEvent for unknown bidId={} — skipping", event.getBidId());
            return;
        }
        checkAndCompleteIfDone(bidOpt.get().getAnnouncementId(), event.getBidId(), true);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVoyageurNoShow(VoyageurNoShowEvent event) {
        Optional<BidEntity> bidOpt = bidRepository.findById(event.getBidId());
        if (bidOpt.isEmpty()) {
            log.warn("VoyageurNoShowEvent for unknown bidId={} — skipping", event.getBidId());
            return;
        }
        checkAndCompleteIfDone(bidOpt.get().getAnnouncementId(), event.getBidId(), false);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onParcelRefused(ParcelRefusedEvent event) {
        Optional<BidEntity> bidOpt = bidRepository.findById(event.getBidId());
        if (bidOpt.isEmpty()) {
            log.warn("ParcelRefusedEvent for unknown bidId={} — skipping", event.getBidId());
            return;
        }
        checkAndCompleteIfDone(bidOpt.get().getAnnouncementId(), event.getBidId(), false);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onHandoverExpired(HandoverExpiredEvent event) {
        if (event.announcementId() == null) {
            return;
        }
        checkAndCompleteIfDone(event.announcementId(), event.bidId(), false);
    }

    /**
     * @param triggeringBidDelivered le colis déclencheur vient d'être livré (DeliveryConfirmedEvent) :
     *                               inutile de chercher un colis livré en base
     */
    private void checkAndCompleteIfDone(UUID announcementId, UUID triggeringBidId,
                                        boolean triggeringBidDelivered) {
        Optional<AnnouncementEntity> announcementOpt = announcementRepository.findById(announcementId);
        if (announcementOpt.isEmpty()) {
            log.warn("Completion check for unknown announcementId={} (triggering bid={}) — skipping",
                    announcementId, triggeringBidId);
            return;
        }

        AnnouncementEntity announcement = announcementOpt.get();

        if (announcement.getStatus() == AnnouncementStatus.COMPLETED
                || announcement.getStatus() == AnnouncementStatus.CANCELLED
                // Lot B (correction 4) : une annonce retirée par la modération ne doit jamais
                // être réécrite en COMPLETED — sinon un événement de livraison asynchrone
                // écraserait REMOVED_BY_ADMIN et laisserait une entrée fallacieuse dans
                // audit_log (immuable).
                || announcement.getStatus() == AnnouncementStatus.REMOVED_BY_ADMIN) {
            return;
        }

        // A trip is done only when NO bid is still in flight. "In flight" =
        // ACCEPTED (paid, awaiting handover), HANDED_OVER, IN_TRANSIT or ARRIVED
        // (landed, awaiting pickup). Checking ACCEPTED alone wrongly completed a
        // trip whose other parcels were still HANDED_OVER/IN_TRANSIT — the trip
        // then vanished from "À venir"/"En cours" into "Historique" with a delivery
        // still ongoing. ARRIVED reopens exactly the same hole after a grouped
        // markArrived: the first confirmed delivery would complete the trip while
        // the other parcels are still waiting to be picked up.
        boolean stillHasInFlightBids = bidRepository.existsByAnnouncementIdAndStatusIn(
                announcementId, List.copyOf(BidStatus.IN_FLIGHT));
        if (stillHasInFlightBids) {
            return;
        }

        // YADONY-FLUTTER-PROD-15 : refuser à la remise le seul colis d'un trajet, avant le
        // départ, le passait « Terminé » et le sortait des trajets actifs. Plus aucun colis en
        // vol ne suffit pas : il faut qu'un colis ait été livré, ou que le trajet soit parti.
        boolean anyDelivered = triggeringBidDelivered
                || bidRepository.existsByAnnouncementIdAndStatus(announcementId, BidStatus.COMPLETED);
        boolean underway = announcement.getStatus() == AnnouncementStatus.IN_PROGRESS;
        if (!anyDelivered && !underway) {
            log.info("Announcement {} left {} : no parcel in flight nor delivered, trip not underway "
                    + "(triggering bid={})", announcementId, announcement.getStatus(), triggeringBidId);
            return;
        }

        AnnouncementStatus previousStatus = announcement.getStatus();
        announcement.setStatus(AnnouncementStatus.COMPLETED);
        announcementRepository.save(announcement);
        // FLUTTER-FC : le trajet quitte « Trajets actifs ». Publié dans la transaction
        // REQUIRES_NEW, l'éviction suit son commit (l'éviction sur DeliveryConfirmedEvent
        // peut passer avant ce passage en COMPLETED).
        eventPublisher.publishEvent(
                new TripActivityChangedEvent(announcement.getId(), announcement.getTravelerId()));
        // Vie privée : le tracking efface les positions floues du voyageur (package tracking).
        eventPublisher.publishEvent(new AnnouncementCompletedEvent(announcement.getId()));

        auditService.log(
                "ANNOUNCEMENT",
                announcement.getTravelerId(),
                "ANNOUNCEMENT_COMPLETED",
                announcement.getId(),
                Map.of(
                        "previousStatus", previousStatus.name(),
                        "lastDeliveredBidId", triggeringBidId.toString(),
                        "trigger", anyDelivered ? "LAST_PARCEL_RESOLVED" : "UNDERWAY_NO_PARCEL_DELIVERED"
                )
        );

        log.info("Announcement {} → COMPLETED (triggering bid={}, previousStatus={})",
                announcement.getId(), triggeringBidId, previousStatus);
    }
}
