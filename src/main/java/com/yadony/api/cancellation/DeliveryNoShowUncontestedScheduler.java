package com.yadony.api.cancellation;

import com.yadony.api.common.AuditService;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Signalement d'absence à la livraison (scope DELIVERY) expiré sans
 * contestation → ouvre un litige "non contesté", sauf pour un signalement « destinataire
 * absent » fait avec la procédure FLUTTER-E2 (garde du colis) : il passe CONFIRMED et la garde
 * continue jusqu'au passage « non réclamé » ({@link UnclaimedParcelScheduler}). Jamais de capture ni de
 * remboursement automatique ici : l'admin tranche toujours via
 * {@code AdminDisputesController.resolveDispute}.
 *
 * <p>L'ouverture du litige passe par {@link DisputeOpenedEvent} plutôt que par un
 * appel direct à {@code DisputeService} (package {@code disputes}) : CLAUDE.md
 * impose les Spring Events pour toute communication cross-package, et le chemin
 * "contesté" analogue ({@link CancellationService#contestDeliveryNoShow}) publie
 * déjà ce même event, consommé par {@code DisputeOpenedEventListener} qui route
 * vers {@code disputeService.openDeliveryNoShowDispute} (idempotent par
 * (bidId, type)). Réutiliser cet event fait converger les deux chemins
 * (contesté / non contesté) sur le même point d'entrée d'ouverture de litige.
 *
 * <p>Idempotent : chaque entité traitée est marquée {@link CancellationStatus#CONFIRMED}
 * avant publication de l'event, afin de ne plus être resélectionnée par
 * {@link CancellationRepository#findExpiredPendingByScope} (qui ne retourne que les
 * entités {@code PENDING_CONFIRMATION}) — sans cela l'event serait republié à
 * chaque exécution horaire tant que l'admin n'a pas résolu le litige, ce qui
 * renverrait une notification et incrémenterait un compteur métrique à chaque
 * passage (voir {@code NotificationDispatcher}/{@code BusinessMetricsListener}
 * qui écoutent {@link DisputeOpenedEvent}).
 */
@Component
public class DeliveryNoShowUncontestedScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryNoShowUncontestedScheduler.class);

    private final CancellationRepository cancellationRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public DeliveryNoShowUncontestedScheduler(CancellationRepository cancellationRepository,
                                              BidRepository bidRepository,
                                              AnnouncementRepository announcementRepository,
                                              ApplicationEventPublisher eventPublisher,
                                              AuditService auditService) {
        this.cancellationRepository = cancellationRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @Transactional
    public void run() {
        List<CancellationEntity> expired = cancellationRepository
                .findExpiredPendingByScope(CancellationScope.DELIVERY, OffsetDateTime.now());
        if (expired.isEmpty()) return;

        // Batch les fetch bid/annonce (évite un N+1 : un findById par cancellation).
        List<UUID> bidIds = expired.stream().map(CancellationEntity::getBidId).distinct().toList();
        Map<UUID, BidEntity> bidsById = bidRepository.findAllById(bidIds).stream()
                .collect(Collectors.toMap(BidEntity::getId, b -> b));

        Map<UUID, AnnouncementEntity> announcementsById;
        if (bidsById.isEmpty()) {
            announcementsById = Map.of();
        } else {
            List<UUID> announcementIds = bidsById.values().stream()
                    .map(BidEntity::getAnnouncementId).distinct().toList();
            announcementsById = announcementRepository.findAllById(announcementIds).stream()
                    .collect(Collectors.toMap(AnnouncementEntity::getId, a -> a));
        }

        expired.forEach(c -> openUncontestedDispute(c, bidsById, announcementsById));
    }

    private void openUncontestedDispute(CancellationEntity c, Map<UUID, BidEntity> bidsById,
                                         Map<UUID, AnnouncementEntity> announcementsById) {
        BidEntity bid = bidsById.get(c.getBidId());
        if (bid == null) {
            log.warn("DeliveryNoShowUncontestedScheduler: bid {} introuvable pour cancellation {} — ignoré",
                    c.getBidId(), c.getId());
            return;
        }
        AnnouncementEntity announcement = announcementsById.get(bid.getAnnouncementId());
        if (announcement == null) {
            log.warn("DeliveryNoShowUncontestedScheduler: annonce {} introuvable pour bid {} (cancellation {}) — ignoré",
                    bid.getAnnouncementId(), c.getBidId(), c.getId());
            return;
        }
        openUncontestedDispute(c, bid, announcement);
    }

    /**
     * Passe la déclaration DELIVERY en CONFIRMED et ouvre le litige « non contesté »
     * ({@link DisputeOpenedEvent}, idempotent par (bidId, type) côté disputes).
     * Partagé avec la confirmation admin ({@link NoShowArbitrationService#confirm}),
     * pour que l'échéance et la décision admin ouvrent exactement le même litige.
     */
    void openUncontestedDispute(CancellationEntity c, BidEntity bid, AnnouncementEntity announcement) {
        c.setNoShowStatus(CancellationStatus.CONFIRMED);
        cancellationRepository.save(c);

        // Procédure « destinataire absent » (FLUTTER-E2) : un signalement fait avec la
        // procédure porte une garde. Non contesté, il ne devient plus un litige : la garde
        // continue et, à son terme sans livraison, le colis passe « non réclamé »
        // (UnclaimedParcelScheduler). Les signalements antérieurs à V301 (sans garde) gardent
        // l'ancien chemin, sinon ils resteraient sans issue.
        if (DeliveryNoShowTypes.isRecipientNoShow(c.getReason()) && c.getHoldUntil() != null) {
            auditService.log("CANCELLATION", c.getId(), "DELIVERY_NOSHOW_UNCONTESTED_HOLD_CONTINUES", null,
                    Map.of("bidId", c.getBidId().toString(), "holdUntil", c.getHoldUntil().toString()));
            return;
        }

        String type = DeliveryNoShowTypes.uncontestedDisputeType(c.getReason());

        eventPublisher.publishEvent(new DisputeOpenedEvent(
                c.getBidId(), bid.getSenderId(), announcement.getTravelerId(), type));

        auditService.log("CANCELLATION", c.getId(), "DELIVERY_NOSHOW_UNCONTESTED_DISPUTE_OPENED", null,
                Map.of("bidId", c.getBidId().toString(), "type", type));
    }
}
