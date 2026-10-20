package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.events.HandoverExpiredEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.payments.cash.CommissionStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Logique métier partagée pour marquer un voyageur en no-show. Extraite du
 * {@code NoShowScheduler} (cron) afin d'être réutilisée par le signalement manuel
 * de l'expéditeur (via {@code TravelerNoShowReportListener}).
 */
@Service
public class NoShowService {

    private static final Logger log = LoggerFactory.getLogger(NoShowService.class);
    private static final int RECURRING_NO_SHOW_THRESHOLD = 2;

    /**
     * Motif posé dans {@code rejectionReason} d'un colis espèces annulé faute de remise
     * confirmée ({@link #expireUnconfirmedCashHandover}). Inconnu de l'app, qui affiche alors
     * le bloc terminal générique d'un colis annulé.
     */
    public static final String HANDOVER_NOT_CONFIRMED_REASON = "HANDOVER_NOT_CONFIRMED";

    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public NoShowService(BidRepository bidRepository,
                         UserRepository userRepository,
                         AnnouncementRepository announcementRepository,
                         AuditService auditService,
                         ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Colis payé en espèces dont la remise n'a été ni scannée ni signalée par l'une ou l'autre
     * partie à l'échéance (date limite + grâce) : rien ne permet de savoir qui n'est pas venu
     * (FLUTTER-QR, décision du propriétaire : « pas de fautif automatique »).
     *
     * <p>Le colis passe en {@code CANCELLED} (motif {@link #HANDOVER_NOT_CONFIRMED_REASON}),
     * personne n'est sanctionné (aucun {@code noShowCount} incrémenté, aucun
     * {@link VoyageurNoShowEvent}) et {@link HandoverExpiredEvent} fait rendre au voyageur la
     * commission prélevée, rendre le code promo et prévenir les deux parties.
     *
     * <p>Idempotent et prudent : le colis est relu sous verrou, rien ne se passe s'il n'est
     * plus ACCEPTED (signalement, scan ou passage concurrent) ou s'il n'est pas en espèces.
     * Les signalements en cours (absence de l'expéditeur en attente ou contestée) sont déjà
     * écartés par {@link BidRepository#findNoShowBids}.
     *
     * @return {@code true} si le colis a été annulé par ce passage
     */
    @Transactional
    public boolean expireUnconfirmedCashHandover(UUID bidId) {
        // Verrou de ligne (UPDATE neutre, comme BidService) : sérialise avec un scan de remise,
        // un signalement ou une annulation concurrents.
        bidRepository.lockForUpdate(bidId);
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null || bid.getStatus() != BidStatus.ACCEPTED
                || bid.getPaymentMethod() != PaymentMethod.CASH) {
            return false;
        }

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);

        // Colis annulé avant toute remise : ses kilos reviennent au trajet (FULL → ACTIVE), comme
        // pour toute annulation d'un colis ACCEPTED. Sans cela, le trajet resté ouvert
        // (AnnouncementCompletionListener ne le clôt plus sans colis livré) gardait une soute
        // pleine de colis qui n'existent plus. Après le départ, rien à rendre.
        if (announcement != null
                && !com.yadony.api.cancellation.CancellationGuard.hasDeparted(announcement)
                && announcement.releaseCapacity(bid.getWeightKg())) {
            announcementRepository.save(announcement);
        }

        bid.setStatus(BidStatus.CANCELLED);
        bid.setRejectionReason(HANDOVER_NOT_CONFIRMED_REASON);
        bidRepository.save(bid);

        UUID travelerId = announcement != null ? announcement.getTravelerId() : null;
        boolean commissionCharged = bid.getCommissionStatus() == CommissionStatus.CHARGED;

        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("source", "scheduler");
        audit.put("reason", HANDOVER_NOT_CONFIRMED_REASON);
        audit.put("paymentMethod", PaymentMethod.CASH.name());
        audit.put("senderId", String.valueOf(bid.getSenderId()));
        audit.put("travelerId", String.valueOf(travelerId));
        audit.put("handoverDeadline", String.valueOf(bid.getHandoverDeadline()));
        audit.put("commissionStatus", String.valueOf(bid.getCommissionStatus()));
        audit.put("commissionChargedVia", String.valueOf(bid.getCommissionChargedVia()));
        auditService.log("BID", bid.getId(), "BID_HANDOVER_EXPIRED", null, audit);

        eventPublisher.publishEvent(new HandoverExpiredEvent(
                bid.getId(), bid.getAnnouncementId(), bid.getSenderId(), travelerId, commissionCharged));

        log.info("Remise espèces non confirmée : bid={} annulé sans fautif (commission {})",
                bid.getId(), bid.getCommissionStatus());
        return true;
    }

    /**
     * Marque un bid NO_SHOW (voyageur absent), incrémente le compteur du voyageur et
     * publie {@link VoyageurNoShowEvent} (→ remboursement escrow). Idempotent : no-op si
     * le bid n'est plus ACCEPTED (déjà traité par le cron ou un signalement concurrent).
     *
     * @param bidId le bid concerné
     * @param source "scheduler" | "sender_report" — tracé dans l'audit.
     */
    @Transactional
    public void recordTravelerNoShow(UUID bidId, String source) {
        BidEntity bid = bidRepository.findById(bidId).orElse(null);
        if (bid == null || bid.getStatus() != BidStatus.ACCEPTED) {
            return; // idempotent
        }

        bid.setStatus(BidStatus.NO_SHOW);
        bid.setNoShowAt(LocalDateTime.now(ZoneOffset.UTC));
        bidRepository.save(bid);

        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
        if (announcement == null) return;

        UserEntity traveler = userRepository.findById(announcement.getTravelerId()).orElse(null);
        if (traveler == null) return;

        int newCount = traveler.getNoShowCount() + 1;
        traveler.setNoShowCount(newCount);
        userRepository.save(traveler);

        auditService.log("BID", bid.getId(), "BID_NO_SHOW", traveler.getId(),
                Map.of("noShowCount", newCount, "senderId", bid.getSenderId().toString(), "source", source));

        // VoyageurNoShowEvent triggers: notification to sender + escrow refund (via PaymentNoShowListener)
        eventPublisher.publishEvent(new VoyageurNoShowEvent(
                bid.getId(), traveler.getId(), bid.getSenderId(), newCount));

        log.info("No-show recorded for bid={} traveler={} noShowCount={} source={}",
                bid.getId(), traveler.getId(), newCount, source);

        if (newCount >= RECURRING_NO_SHOW_THRESHOLD) {
            auditService.log("USER", traveler.getId(), "ADMIN_ALERT_RECURRING_NO_SHOW", traveler.getId(),
                    Map.of("noShowCount", newCount, "bidId", bid.getId().toString()));
            log.warn("Recurring no-show alert for traveler {}: count={}", traveler.getId(), newCount);
        }
    }
}
