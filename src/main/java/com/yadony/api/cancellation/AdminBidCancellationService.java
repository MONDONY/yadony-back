package com.yadony.api.cancellation;

import com.yadony.api.cancellation.dto.AdminBidCancelRequest;
import com.yadony.api.cancellation.dto.AdminBidCancelResponse;
import com.yadony.api.cancellation.events.AdminBidCancelledEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.disputes.DisputeTypes;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Annulation d'un colis décidée par l'administration (décision propriétaire du 2026-10-10).
 *
 * <p>Réutilise le flux d'annulation existant plutôt que de le réécrire : le colis passe
 * {@code CANCELLED}, la capacité est rendue au trajet, puis {@link BidRejectedEvent} (motif
 * {@code CANCELLED_BY_ADMIN}, sans rematch) déclenche exactement ce qu'une annulation déclenche
 * déjà : remboursement intégral par {@code payments.RefundProcessor} (annulation de
 * l'autorisation si le paiement n'est pas capturé, Refund s'il l'est, jamais de versement au
 * voyageur), remboursement d'une commission espèces, libération d'un code promo, information
 * du destinataire. {@link AdminBidCancelledEvent} prévient les deux parties et trace
 * l'annulation dans la conversation, qui est conservée.
 *
 * <p>Aucune annulation n'est comptée dans la fiabilité de qui que ce soit : c'est une décision
 * de la plateforme. Aucune pénalité financière.
 *
 * <p>Idempotent : un colis déjà annulé répond 200 {@code alreadyCancelled}, sans rien refaire.
 */
@Service
public class AdminBidCancellationService {

    /** Statuts où le colis est terminé autrement que par une annulation : rien à annuler. */
    static final Set<BidStatus> CLOSED_STATUSES = EnumSet.of(
            BidStatus.REJECTED, BidStatus.EXPIRED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED,
            BidStatus.NEGOTIATION_CLOSED);

    static final int OTHER_NOTE_MIN_LENGTH = 10;

    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final PaymentRepository paymentRepository;
    private final DisputeRepository disputeRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AdminBidCancellationService(BidRepository bidRepository,
                                       AnnouncementRepository announcementRepository,
                                       PaymentRepository paymentRepository,
                                       DisputeRepository disputeRepository,
                                       AuditService auditService,
                                       ApplicationEventPublisher eventPublisher) {
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.paymentRepository = paymentRepository;
        this.disputeRepository = disputeRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    @CacheEvict(value = {"announcements-search", "traveler-bids-me", "bids-me"}, allEntries = true)
    public AdminBidCancelResponse cancel(UUID bidId, UUID adminId, AdminBidCancelRequest request) {
        String note = request.note() != null ? request.note().trim() : "";
        if (request.reason() == AdminBidCancelReason.OTHER && note.length() < OTHER_NOTE_MIN_LENGTH) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "cancel-note-required",
                    "Note Required", "Précisez le motif « Autre » en 10 caractères au moins.");
        }

        // Verrou de ligne : deux clics simultanés ne peuvent pas annuler (et notifier) deux fois.
        BidEntity bid = bidRepository.findByIdForUpdate(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "bid-not-found",
                        "Not Found", "Colis introuvable"));

        Optional<PaymentEntity> payment = paymentRepository.findForBid(bidId);
        String currency = payment.map(PaymentEntity::getCurrency).orElse(bid.getCurrency());

        if (bid.getStatus() == BidStatus.CANCELLED) {
            return new AdminBidCancelResponse(bidId, BidStatus.CANCELLED.name(), BidStatus.CANCELLED.name(),
                    true, false, payment.map(p -> p.getStatus().name()).orElse(null),
                    BigDecimal.ZERO, currency, false);
        }
        assertCancellable(bid, payment);

        BidStatus previous = bid.getStatus();
        AnnouncementEntity announcement = bid.getAnnouncementId() != null
                ? announcementRepository.findById(bid.getAnnouncementId()).orElse(null) : null;
        boolean parcelWithTraveler = BidStatus.EN_ROUTE.contains(previous);

        releaseCapacityIfReserved(bid, announcement);
        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);

        boolean refundRequested = payment.isPresent()
                && (payment.get().getStatus() == PaymentStatus.ESCROW
                    || payment.get().getStatus() == PaymentStatus.PENDING);
        BigDecimal refundAmount = payment
                .filter(p -> p.getStatus() == PaymentStatus.ESCROW)
                .map(p -> p.getAmount().subtract(p.getRefundedAmount() != null ? p.getRefundedAmount() : BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
        String paymentStatus = payment.map(p -> p.getStatus().name()).orElse(null);

        Map<String, Object> payload = new HashMap<>();
        payload.put("reason", request.reason().name());
        payload.put("note", note);
        payload.put("previousStatus", previous.name());
        payload.put("paymentStatus", String.valueOf(paymentStatus));
        payload.put("refundRequested", refundRequested);
        payload.put("refundAmount", refundAmount.toPlainString());
        payload.put("currency", String.valueOf(currency));
        payload.put("parcelWithTraveler", parcelWithTraveler);
        auditService.log("BID", bidId, "ADMIN_BID_CANCELLED", adminId, payload);

        eventPublisher.publishEvent(new BidRejectedEvent(bidId, bid.getSenderId(),
                BidRejectedEvent.REASON_CANCELLED_BY_ADMIN, bid.getAnnouncementId(), false));
        eventPublisher.publishEvent(new AdminBidCancelledEvent(bidId, bid.getAnnouncementId(), bid.getSenderId(),
                announcement != null ? announcement.getTravelerId() : null, adminId,
                refundRequested, refundAmount, currency, parcelWithTraveler));

        return new AdminBidCancelResponse(bidId, BidStatus.CANCELLED.name(), previous.name(), false,
                refundRequested, paymentStatus, refundAmount, currency, parcelWithTraveler);
    }

    private void assertCancellable(BidEntity bid, Optional<PaymentEntity> payment) {
        if (bid.getStatus() == BidStatus.COMPLETED) {
            throw conflict("bid-delivered", "Colis déjà livré : il ne peut plus être annulé.");
        }
        if (bid.getStatus() == BidStatus.NEGOTIATING) {
            throw conflict("bid-not-a-parcel",
                    "Ce n'est qu'une discussion de prix, pas un colis réservé : rien à annuler.");
        }
        if (CLOSED_STATUSES.contains(bid.getStatus())) {
            throw conflict("bid-already-closed",
                    "Ce colis est déjà terminé (statut " + bid.getStatus() + ") : rien à annuler.");
        }
        if (payment.isPresent() && payment.get().getStatus() == PaymentStatus.RELEASED) {
            throw conflict("payment-released",
                    "Le voyageur a déjà été payé pour ce colis : l'annulation rembourserait un argent déjà versé.");
        }
        if (payment.isPresent() && payment.get().isDisputed()) {
            throw conflict("payment-disputed",
                    "Un litige bancaire est ouvert sur ce paiement : attendez la décision de la banque.");
        }
        if (disputeRepository.existsByBidIdAndStatusNot(bid.getId(), DisputeTypes.STATUS_RESOLVED)) {
            throw conflict("dispute-open",
                    "Un litige est ouvert sur ce colis : résolvez-le d'abord dans Incidents.");
        }
    }

    /**
     * Même règle que {@code BidService#restoreCapacityIfNeeded} : la capacité n'a été prélevée
     * qu'à l'acceptation (ou à l'attente d'un paiement mobile money). Rien n'est rendu une fois
     * le trajet parti : l'annonce ne doit pas réapparaître en recherche.
     */
    private void releaseCapacityIfReserved(BidEntity bid, AnnouncementEntity announcement) {
        if (announcement == null || CancellationGuard.hasDeparted(announcement)) {
            return;
        }
        boolean awaitingMobileMoney = bid.getStatus() == BidStatus.AWAITING_PAYMENT
                && bid.getPaymentMethod() == PaymentMethod.MOBILE_MONEY;
        boolean reserved = bid.getStatus() == BidStatus.ACCEPTED || bid.getStatus() == BidStatus.HANDED_OVER
                || awaitingMobileMoney;
        if (reserved && announcement.releaseCapacity(bid.getWeightKg())) {
            announcementRepository.save(announcement);
        }
    }

    private static YadonyBusinessException conflict(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.CONFLICT, code, "Bid Not Cancellable", detail);
    }
}
