package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.BidLostRematchPreparedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.events.BidRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rematch bid-only : quand le voyageur annule le transport d'un colis payé
 * ({@code cancelBid}) ou refuse une demande déjà payée ({@code rejectBid}), sans annuler le
 * trajet lui-même, ce listener crée une {@code CancellationEntity} dédiée (scope
 * {@code HANDOVER} par défaut) et réutilise {@link RematchService#generateForCancellations}
 * TEL QUEL pour générer les suggestions de rematch — même logique que
 * {@code CancellationService.cancelTrip}, mais pour un seul bid.
 *
 * <p>Synchrone ({@code @EventListener}, pas {@code @TransactionalEventListener}) : la
 * cancellation et les suggestions committent dans la MÊME transaction que
 * {@code cancelBid}/{@code rejectBid} — même garantie que la génération inline dans
 * {@code cancelTrip}.
 *
 * <p>Ne réagit qu'aux events {@link BidRejectedEvent#isRematchEligible()}. Si l'annonce ou
 * le bid sont introuvables, log un warning et ne fait rien : aucune exception n'est levée
 * ici, car le remboursement AFTER_COMMIT côté paiements dépend du commit de la transaction
 * appelante — la faire échouer casserait le remboursement.
 *
 * <p>Garde d'unicité : la contrainte {@code UNIQUE(bid_id, scope)} (V173,
 * {@code uq_cancellations_bid_id_scope}) interdit une 2e cancellation HANDOVER sur le même
 * bid — atteignable si une {@code CancellationEntity} SENDER_NO_SHOW (scope HANDOVER défaut)
 * existe déjà pour ce bid ({@code reportSenderNoShow}) avant l'annulation/refus voyageur. On
 * ne réutilise pas cette cancellation existante (sémantique différente, {@code noShowStatus}
 * en cours) : on publie quand même {@link BidLostRematchPreparedEvent} avec
 * {@code cancellationId = null} et {@code suggestionCount = 0} pour que le dispatcher envoie
 * la notification « remboursement en cours » sans deep link — sinon l'expéditeur ne
 * recevrait AUCUNE notification (la générique est sautée dès que {@code rematchEligible}).
 */
@Component
public class BidLostRematchListener {

    private static final Logger log = LoggerFactory.getLogger(BidLostRematchListener.class);

    private static final String REASON_CANCELLED_BY_TRAVELER = "CANCELLED_BY_TRAVELER";

    /**
     * Motifs « initiés par le voyageur » (revue round 3) : au-delà du refus/annulation
     * explicite d'un bid par le voyageur, la suppression de son propre trajet
     * ({@code AnnouncementService#removeByAdmin}/{@code deleteAnnouncement}, motif
     * {@link BidEntity#REJECTION_ANNOUNCEMENT_DELETED}) porte la même nature : c'est le
     * voyageur qui a agi (ou la modération en son nom), pas un refus explicite du colis. Le
     * libellé final distingue quand même les deux cas (cf.
     * {@code NotificationDispatcher#onBidLostRematchPrepared}, sur {@code reason}), mais les
     * deux ouvrent droit au même traitement rematch.
     */
    private static final Set<String> TRAVELER_INITIATED_REASONS =
            Set.of(REASON_CANCELLED_BY_TRAVELER, BidEntity.REJECTION_ANNOUNCEMENT_DELETED);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final CancellationRepository cancellationRepository;
    private final RematchService rematchService;
    private final ApplicationEventPublisher eventPublisher;

    public BidLostRematchListener(BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   CancellationRepository cancellationRepository,
                                   RematchService rematchService,
                                   ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.cancellationRepository = cancellationRepository;
        this.rematchService = rematchService;
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onBidRejected(BidRejectedEvent event) {
        if (!event.isRematchEligible()) {
            return;
        }

        boolean cancelledByTraveler = TRAVELER_INITIATED_REASONS.contains(event.getReason());

        if (cancellationRepository.findByBidId(event.getBidId()).isPresent()) {
            log.warn("BidLostRematchListener: cancellation HANDOVER déjà existante pour bid {}, "
                            + "rematch non généré (probable no-show en cours)", event.getBidId());
            eventPublisher.publishEvent(new BidLostRematchPreparedEvent(
                    event.getSenderId(), event.getBidId(), null, 0, cancelledByTraveler, event.getReason()));
            return;
        }

        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("BidLostRematchListener: bid {} introuvable, rematch ignoré", event.getBidId());
            return;
        }

        UUID announcementId = event.getAnnouncementId() != null
                ? event.getAnnouncementId()
                : bid.getAnnouncementId();
        AnnouncementEntity announcement = announcementId != null
                ? announcementRepository.findById(announcementId).orElse(null)
                : null;
        if (announcement == null) {
            log.warn("BidLostRematchListener: annonce {} introuvable pour bid {}, rematch ignoré",
                    announcementId, event.getBidId());
            return;
        }

        CancellationEntity cancellation = new CancellationEntity();
        cancellation.setBidId(bid.getId());
        cancellation.setCancelledBy(announcement.getTravelerId());
        cancellation.setReason(cancelledByTraveler
                ? CancellationReason.BID_CANCELLED_BY_TRAVELER.name()
                : CancellationReason.BID_REJECTED_AFTER_PAYMENT.name());
        cancellation = cancellationRepository.save(cancellation);

        Map<UUID, RematchService.RematchInfo> bySender = rematchService.generateForCancellations(
                announcement, List.of(bid), List.of(cancellation));
        RematchService.RematchInfo info = bySender.get(bid.getSenderId());
        int suggestionCount = info != null ? info.suggestionCount() : 0;

        eventPublisher.publishEvent(new BidLostRematchPreparedEvent(
                bid.getSenderId(), bid.getId(), cancellation.getId(), suggestionCount,
                cancelledByTraveler, event.getReason()));
    }
}
