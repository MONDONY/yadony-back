package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.NoShowAdminDecisionEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Arbitrage admin d'une déclaration de no-show (écran Incidents > No-shows).
 *
 * <p>Seule une déclaration encore ouverte (PENDING_CONFIRMATION ou CONTESTED) se tranche,
 * sinon 409 {@code noshow-already-decided}. Effets par portée :
 * <ul>
 *   <li><b>Confirmer HANDOVER</b> : même chemin que la confirmation historique
 *       ({@link CancellationConfirmedEvent} SENDER_NO_SHOW : bid CANCELLED, remboursement du
 *       séquestre, commission espèces remboursée, compteur de réputation). Un litige de
 *       contestation ouvert est fermé (NOSHOW_CONFIRMED) : la décision admin le tranche.</li>
 *   <li><b>Confirmer DELIVERY</b> : aucune conséquence financière ici. En attente : ouvre le
 *       litige « non contesté » exactement comme l'échéance
 *       ({@link DeliveryNoShowUncontestedScheduler#openUncontestedDispute}). Contestée : le
 *       litige de contestation existe déjà et reste ouvert, l'admin y tranche l'argent.</li>
 *   <li><b>Rejeter</b> (les deux portées) : la déclaration est classée (RESOLVED +
 *       {@code admin_decision = REJECTED}), le bid continue sans annulation ni remboursement,
 *       et les schedulers d'échéance ne la voient plus (ils ne lisent que PENDING_CONFIRMATION).
 *       Un litige lié encore ouvert est fermé (NOSHOW_REJECTED).</li>
 * </ul>
 * La fermeture des litiges et les notifications passent par {@link NoShowAdminDecisionEvent}
 * (règle cross-package : jamais d'appel direct au service des litiges).
 */
@Service
public class NoShowArbitrationService {

    /** Motif enregistré quand l'ancien {@code POST /cancellations/bids/{bidId}/confirm-noshow} tranche. */
    static final String LEGACY_DECISION_REASON =
            "Confirmation par l'ancien point d'entrée confirm-noshow (sans motif saisi)";

    private final CancellationRepository cancellationRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final DeliveryNoShowUncontestedScheduler deliveryScheduler;

    public NoShowArbitrationService(CancellationRepository cancellationRepository,
                                    BidRepository bidRepository,
                                    AnnouncementRepository announcementRepository,
                                    AuditService auditService,
                                    ApplicationEventPublisher eventPublisher,
                                    DeliveryNoShowUncontestedScheduler deliveryScheduler) {
        this.cancellationRepository = cancellationRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.deliveryScheduler = deliveryScheduler;
    }

    @Transactional
    public CancellationEntity confirm(UUID cancellationId, UUID adminId, String decisionReason) {
        return doConfirm(loadOpenNoShow(cancellationId), adminId, decisionReason);
    }

    @Transactional
    public CancellationEntity reject(UUID cancellationId, UUID adminId, String decisionReason) {
        CancellationEntity c = loadOpenNoShow(cancellationId);
        CancellationStatus previous = c.getNoShowStatus();
        BidEntity bid = loadBid(c);
        AnnouncementEntity announcement = findAnnouncement(bid);

        markDecision(c, NoShowAdminDecision.REJECTED, adminId, decisionReason);
        c.setNoShowStatus(CancellationStatus.RESOLVED);
        cancellationRepository.save(c);

        audit(c, "NOSHOW_REJECTED_BY_ADMIN", adminId, previous, decisionReason);
        publishDecision(c, NoShowAdminDecision.REJECTED, bid, announcement, adminId,
                NoShowReasons.linkedDisputeTypes(c.getReason()));
        return c;
    }

    /**
     * Ancien {@code POST /cancellations/bids/{bidId}/confirm-noshow} : même contrat qu'avant
     * (404 sans ligne HANDOVER, 200 sans effet si elle n'est plus ouverte), mais la décision
     * passe désormais par {@link #confirm} et couvre aussi une déclaration contestée.
     */
    @Transactional
    public void confirmLegacyByBid(UUID bidId, UUID adminId) {
        CancellationEntity c = cancellationRepository.findByBidId(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "cancellation-not-found", "Not Found",
                        "Aucune annulation en attente pour ce bid"));
        if (!NoShowReasons.isNoShow(c.getReason()) || !isOpen(c)) {
            return;
        }
        doConfirm(c, adminId, LEGACY_DECISION_REASON);
    }

    private CancellationEntity doConfirm(CancellationEntity c, UUID adminId, String decisionReason) {
        CancellationStatus previous = c.getNoShowStatus();
        BidEntity bid = loadBid(c);
        AnnouncementEntity announcement = findAnnouncement(bid);

        markDecision(c, NoShowAdminDecision.CONFIRMED, adminId, decisionReason);
        List<String> disputesToClose;
        if (c.getScope() == CancellationScope.HANDOVER) {
            c.setNoShowStatus(CancellationStatus.CONFIRMED);
            cancellationRepository.save(c);
            eventPublisher.publishEvent(
                    new CancellationConfirmedEvent(c.getBidId(), c.getId(), CancellationReason.SENDER_NO_SHOW));
            disputesToClose = NoShowReasons.linkedDisputeTypes(c.getReason());
        } else {
            if (previous == CancellationStatus.PENDING_CONFIRMATION) {
                if (announcement == null) {
                    throw new YadonyBusinessException(HttpStatus.NOT_FOUND, "announcement-not-found",
                            "Not Found", "Annonce introuvable pour ce bid");
                }
                deliveryScheduler.openUncontestedDispute(c, bid, announcement);
            } else {
                c.setNoShowStatus(CancellationStatus.CONFIRMED);
                cancellationRepository.save(c);
            }
            disputesToClose = List.of();
        }

        audit(c, "NOSHOW_CONFIRMED_BY_ADMIN", adminId, previous, decisionReason);
        publishDecision(c, NoShowAdminDecision.CONFIRMED, bid, announcement, adminId, disputesToClose);
        return c;
    }

    private CancellationEntity loadOpenNoShow(UUID cancellationId) {
        CancellationEntity c = cancellationRepository.findById(cancellationId)
                .filter(found -> NoShowReasons.isNoShow(found.getReason()))
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "noshow-not-found", "Not Found",
                        "Déclaration d'absence introuvable"));
        if (!isOpen(c)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "noshow-already-decided",
                    "No-show Already Decided", "Cette déclaration d'absence est déjà tranchée");
        }
        return c;
    }

    private static boolean isOpen(CancellationEntity c) {
        return c.getNoShowStatus() == CancellationStatus.PENDING_CONFIRMATION
                || c.getNoShowStatus() == CancellationStatus.CONTESTED;
    }

    private BidEntity loadBid(CancellationEntity c) {
        return bidRepository.findById(c.getBidId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
    }

    private AnnouncementEntity findAnnouncement(BidEntity bid) {
        return bid.getAnnouncementId() == null ? null
                : announcementRepository.findById(bid.getAnnouncementId()).orElse(null);
    }

    private static void markDecision(CancellationEntity c, NoShowAdminDecision decision, UUID adminId,
                                     String decisionReason) {
        c.setAdminDecision(decision);
        c.setDecidedByAdminId(adminId);
        c.setDecidedAt(OffsetDateTime.now(ZoneOffset.UTC));
        c.setDecisionReason(decisionReason);
    }

    private void audit(CancellationEntity c, String action, UUID adminId, CancellationStatus previous,
                       String decisionReason) {
        auditService.log("CANCELLATION", c.getId(), action, adminId,
                Map.of("bidId", c.getBidId().toString(),
                        "scope", c.getScope().name(),
                        "reason", c.getReason(),
                        "decisionReason", decisionReason,
                        "previousStatus", previous.name()));
    }

    private void publishDecision(CancellationEntity c, NoShowAdminDecision decision, BidEntity bid,
                                 AnnouncementEntity announcement, UUID adminId, List<String> disputesToClose) {
        eventPublisher.publishEvent(new NoShowAdminDecisionEvent(
                c.getId(), c.getBidId(), c.getScope(), c.getReason(), decision,
                bid.getSenderId(), announcement != null ? announcement.getTravelerId() : null,
                adminId, disputesToClose));
    }
}
