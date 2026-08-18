package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.events.BidMaterializedEvent;
import com.yadony.api.promo.PromoService;
import com.yadony.api.requests.event.PackageRequestAcceptedEvent;
import com.yadony.api.requests.event.PackageRequestDetailsCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bridges the package_request marketplace flow to the classic bid-based
 * "Mes envois" infrastructure. When a NegotiationThread reaches ACCEPTED
 * (sender paid), we materialise an ACCEPTED Bid so the shipment shows up
 * in the sender's "Mes envois → En cours" tab and the rest of the flow
 * (tracking, livraison, capture Stripe) can keep using the existing Bid id.
 *
 * Listens AFTER_COMMIT in REQUIRES_NEW so we never see uncommitted state
 * from NegotiationService.finalizeAfterPayment, and we don't roll back the
 * whole acceptance if the bid creation hiccups.
 */
@Component
public class ThreadAcceptedBidListener {

    private static final Logger log = LoggerFactory.getLogger(ThreadAcceptedBidListener.class);

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final StorageService storageService;
    private final BidPhotoService bidPhotoService;
    private final PromoService promoService;

    public ThreadAcceptedBidListener(BidRepository bidRepository,
                                     AnnouncementRepository announcementRepository,
                                     AuditService auditService,
                                     ApplicationEventPublisher eventPublisher,
                                     StorageService storageService,
                                     BidPhotoService bidPhotoService,
                                     PromoService promoService) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.storageService = storageService;
        this.bidPhotoService = bidPhotoService;
        this.promoService = promoService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPackageRequestAccepted(PackageRequestAcceptedEvent e) {
        // Idempotence: if another listener (or a webhook retry) already
        // materialised the bid for this thread, do nothing.
        if (bidRepository.findByLinkedNegotiationThreadId(e.threadId()).isPresent()) {
            log.debug("Bid already exists for thread {}, skipping creation", e.threadId());
            return;
        }
        if (e.travelerAnnouncementId() == null) {
            log.warn("Cannot create bid for thread {}: travelerAnnouncementId is null", e.threadId());
            return;
        }

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(e.travelerAnnouncementId());
        bid.setSenderId(e.senderId());
        bid.setWeightKg(e.weightKg());
        bid.setDescription(e.description() != null ? e.description() : e.contentCategory());
        bid.setContentCategory(e.contentCategory());
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setPaymentIntentId(e.paymentIntentId());
        bid.setLinkedNegotiationThreadId(e.threadId());
        // Fige le net négocié : l'affichage ne doit pas dériver si l'annonce
        // dédiée ouvre son surplus (réécrit price_per_kg) ou si le trajet lié a
        // un tarif catalogue différent du prix d'accord.
        bid.setNegotiatedNetEur(e.agreedPriceEur());
        // Recipient details + disclaimer are completed by the sender before payment,
        // so they are carried on the event and set here at bid creation. If absent
        // (edited afterwards), onPackageRequestDetailsCompleted re-applies them.
        bid.setRecipientName(e.recipientName());
        bid.setRecipientPhone(e.recipientPhone());
        bid.setDisclaimerSignedAt(e.disclaimerSignedAt());
        bid.setDisclaimerSignedIp(e.disclaimerSignedIp());
        // Tracking artefacts generated at acceptance, mirroring BidService.acceptBid
        // so the marketplace-issued bid exposes the same QR + tracking number as a
        // classic bid (sender's "Mes envois" screen relies on these to render the
        // QR card and the recipient tracking link).
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingNumber(BidService.generateTrackingNumber());
        bid.setTrackingToken(UUID.randomUUID().toString());
        // Carry the negotiation thread's payment method onto the materialised bid so
        // the app shows the correct payment UI (a CASH bid must NOT prompt the sender
        // for a Stripe payment).
        if (e.paymentMethod() != null) {
            bid.setPaymentMethod(e.paymentMethod());
        }
        if (e.paymentMethod() == com.yadony.api.payments.cash.PaymentMethod.CASH) {
            // Commission for cash negotiations is charged on the thread at finalize;
            // mark the bid so the classic cash flow never re-charges and the UI shows "réglée".
            bid.setCommissionStatus(com.yadony.api.payments.cash.CommissionStatus.CHARGED);
            // Carry over HOW it was charged (WALLET/CARD, stamped on the thread by
            // CashCommissionService.settleNegotiationCommission) — without this,
            // BidCancelledCommissionRefundListener sees commissionChargedVia=null on
            // the materialised bid and silently skips the refund if this bid is later
            // cancelled before handover, leaving the traveler's commission un-refunded.
            if (e.commissionChargedVia() != null) {
                bid.setCommissionChargedVia(
                    com.yadony.api.payments.cash.CommissionChargedVia.valueOf(e.commissionChargedVia()));
            }
        }

        announcementRepository.findById(e.travelerAnnouncementId()).ifPresent(announcement -> {
            bid.applyHandoverFrom(announcement);
            boolean isKgFree = announcement.getCapacityUnit() == CapacityUnit.KG_FREE;
            // Dedicated trips (linkedPackageRequestId != null) intentionally start with
            // availableKg = 0 — the full capacity is reserved for the sender. Subtracting
            // weightKg would produce a negative value and violate the DB constraint.
            // Surplus capacity is managed separately via openSurplus().
            boolean isDedicatedTrip = announcement.getLinkedPackageRequestId() != null;
            if (!isKgFree && !isDedicatedTrip && bid.getWeightKg() != null && announcement.getAvailableKg() != null) {
                announcement.setAvailableKg(announcement.getAvailableKg().subtract(bid.getWeightKg()));
                // Lot B (correction 2) : ne jamais réécrire le statut d'une annonce retirée par
                // la modération — le paiement de l'expéditeur est déjà acquis à ce stade (AFTER_COMMIT),
                // on matérialise donc quand même le bid, mais sans ressusciter le trajet en FULL.
                if (announcement.getAvailableKg().compareTo(BigDecimal.ZERO) <= 0
                        && announcement.getStatus() != AnnouncementStatus.REMOVED_BY_ADMIN) {
                    announcement.setStatus(AnnouncementStatus.FULL);
                }
            }
            announcementRepository.save(announcement);
        });
        BidEntity saved = bidRepository.save(bid);

        // Rachat (redeem) du code promo appliqué au paiement, si présent — le bid_id vient
        // d'être créé, c'est le SEUL point où promo_redemptions (UNIQUE(promo_code_id, bid_id))
        // peut être écrit pour un thread de négociation (aucun bid n'existait avant). Best-effort :
        // un échec ne doit pas casser la matérialisation, l'argent a déjà été correctement
        // débité au taux promo par PaymentService.createNegotiationEscrow.
        if (e.promoCode() != null && e.commissionRate() != null) {
            saved.setCommissionRate(e.commissionRate());
            bidRepository.save(saved);
            try {
                promoService.redeem(e.promoCode(), e.senderId(), saved.getId(), e.commissionRate());
            } catch (Exception ex) {
                log.error("Échec rachat promo {} pour bid {} (thread {}): {}",
                    e.promoCode(), saved.getId(), e.threadId(), ex.toString());
            }
        }

        // Les photos colis de la demande (package_requests/…) sont copiées vers bids/ pour
        // donner au bid sa propre copie (lifecycle/cleanup indépendants), puis attachées.
        // Best-effort : un échec de copie ne doit pas casser la matérialisation du bid.
        attachCopiedPhotos(saved.getId(), e.senderId(), e.photoObjectKeys());

        auditService.log("BID", saved.getId(), "CREATED_FROM_THREAD", e.senderId(),
            Map.of(
                "threadId", e.threadId().toString(),
                "packageRequestId", e.packageRequestId().toString(),
                "agreedPrice", e.agreedPriceEur().toString()
            ));
        log.info("Bid {} created from thread {} (announcement {})",
            saved.getId(), e.threadId(), e.travelerAnnouncementId());

        // Notify requests/ so it can stamp materialized_bid_id on the thread and let
        // the mobile app open the bid detail (tracking, no-show…) from the thread.
        // Cross-package boundary: event only, never direct service injection.
        eventPublisher.publishEvent(new BidMaterializedEvent(e.threadId(), saved.getId()));
    }

    /**
     * After the sender fills the post-acceptance details on the package_request
     * (recipient + declared value + disclaimer), propagate them onto the
     * marketplace-issued bid so the existing flow (capture, etc.) sees them.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPackageRequestDetailsCompleted(PackageRequestDetailsCompletedEvent e) {
        var bidOpt = bidRepository.findByLinkedNegotiationThreadId(e.threadId());
        if (bidOpt.isEmpty()) {
            log.debug("No bid for thread {} yet — details will be re-applied on bid creation if it arrives later",
                e.threadId());
            return;
        }
        BidEntity bid = bidOpt.get();
        bid.setRecipientName(e.recipientName());
        bid.setRecipientPhone(e.recipientPhone());
        bid.setDisclaimerSignedAt(e.disclaimerSignedAt());
        bid.setDisclaimerSignedIp(e.disclaimerSignedIp());
        bidRepository.save(bid);
        log.info("Bid {} synced with package_request details (thread {})", bid.getId(), e.threadId());
    }

    /**
     * Copie chaque photo source (package_requests/…) vers bids/{senderId}/ puis les attache
     * au bid. Best-effort : toute erreur de copie/attache est loggée sans interrompre la
     * matérialisation (les photos sont un confort, pas un bloquant métier).
     */
    private void attachCopiedPhotos(UUID bidId, UUID senderId, List<String> sourceKeys) {
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            return;
        }
        List<String> copied = new ArrayList<>();
        for (String src : sourceKeys) {
            try {
                copied.add(storageService.copyObject(src, "bids/" + senderId + "/"));
            } catch (Exception ex) {
                log.error("Échec copie photo demande {} vers bid {}: {}", src, bidId, ex.toString());
            }
        }
        if (!copied.isEmpty()) {
            try {
                bidPhotoService.attachPhotos(bidId, senderId, copied);
            } catch (Exception ex) {
                log.error("Échec attache photos copiées au bid {}: {}", bidId, ex.toString());
            }
        }
    }
}
