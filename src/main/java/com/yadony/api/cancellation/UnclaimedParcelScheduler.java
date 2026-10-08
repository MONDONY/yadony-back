package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.ParcelUnclaimedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Garde échue d'un colis dont le destinataire était absent (FLUTTER-E2) : le colis passe
 * « non réclamé » et le net est libéré au voyageur, qui a fait le transport.
 *
 * <p>Conditions, toutes relues à chaque passage (idempotent) :
 * <ul>
 *   <li>signalement RECIPIENT_NO_SHOW CONFIRMED (non contesté dans le délai ou confirmé par
 *       l'admin), garde ({@code hold_until}) échue, pas encore « non réclamé » ;</li>
 *   <li>colis toujours chez le voyageur (HANDED_OVER, IN_TRANSIT, ARRIVED) : un colis livré
 *       entre-temps (nouveau RDV ou nouveau destinataire) est réglé par la livraison ;</li>
 *   <li>aucun litige sur le colis, ouvert ou résolu : une contestation en cours suspend tout,
 *       et un litige tranché laisse l'argent à la décision de l'admin (partage, remboursement
 *       ou libération), jamais à un versement automatique.</li>
 * </ul>
 *
 * <p>Le passage est un claim atomique ({@link CancellationRepository#markUnclaimed}) : deux
 * instances ou un rejeu ne publient jamais deux {@link ParcelUnclaimedEvent}. Le versement lui-même
 * est fait après commit par {@code payments.DeliveryEventListener#handleParcelUnclaimed}, avec les
 * gardes de la livraison (séquestre, chargeback, remboursement partiel, voyageur gelé, compte
 * Stripe inutilisable) et la même clé d'idempotence Stripe.
 *
 * <p>Le colis reste ARRIVED : un retrait ultérieur par une personne mandatée (changement de
 * destinataire puis code de retrait) reste possible, et la livraison qui suivrait ne verse plus
 * rien (paiement déjà RELEASED).
 */
@Component
public class UnclaimedParcelScheduler {

    private static final Logger log = LoggerFactory.getLogger(UnclaimedParcelScheduler.class);

    private final CancellationRepository cancellationRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final DisputeRepository disputeRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public UnclaimedParcelScheduler(CancellationRepository cancellationRepository,
                                    BidRepository bidRepository,
                                    AnnouncementRepository announcementRepository,
                                    DisputeRepository disputeRepository,
                                    AuditService auditService,
                                    ApplicationEventPublisher eventPublisher) {
        this(cancellationRepository, bidRepository, announcementRepository, disputeRepository,
                auditService, eventPublisher, Clock.systemUTC());
    }

    UnclaimedParcelScheduler(CancellationRepository cancellationRepository,
                             BidRepository bidRepository,
                             AnnouncementRepository announcementRepository,
                             DisputeRepository disputeRepository,
                             AuditService auditService,
                             ApplicationEventPublisher eventPublisher,
                             Clock clock) {
        this.cancellationRepository = cancellationRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.disputeRepository = disputeRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 * * * *", zone = "UTC")
    @Transactional
    public void run() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<CancellationEntity> due = cancellationRepository.findDueUnclaimedHolds(now);
        for (CancellationEntity c : due) {
            process(c, now);
        }
    }

    void process(CancellationEntity c, OffsetDateTime now) {
        BidEntity bid = bidRepository.findById(c.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("UnclaimedParcelScheduler: bid {} introuvable (cancellation {}) — ignoré", c.getBidId(), c.getId());
            return;
        }
        if (!BidStatus.EN_ROUTE.contains(bid.getStatus())) {
            log.info("UnclaimedParcelScheduler: bid {} en {} — plus chez le voyageur, garde close sans versement",
                    bid.getId(), bid.getStatus());
            return;
        }
        if (disputeRepository.existsByBidId(bid.getId())) {
            log.info("UnclaimedParcelScheduler: litige sur le bid {} — l'admin tranche, aucun versement automatique",
                    bid.getId());
            return;
        }
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        if (announcement == null || announcement.getTravelerId() == null) {
            log.warn("UnclaimedParcelScheduler: voyageur introuvable pour le bid {} — ignoré", bid.getId());
            return;
        }
        if (cancellationRepository.markUnclaimed(c.getId(), now) == 0) {
            return;
        }
        auditService.log("CANCELLATION", c.getId(), "PARCEL_UNCLAIMED", null,
                Map.of("bidId", bid.getId().toString(),
                        "holdUntil", String.valueOf(c.getHoldUntil()),
                        "travelerId", announcement.getTravelerId().toString()));
        eventPublisher.publishEvent(new ParcelUnclaimedEvent(
                bid.getId(), bid.getSenderId(), announcement.getTravelerId(), c.getId()));
    }
}
