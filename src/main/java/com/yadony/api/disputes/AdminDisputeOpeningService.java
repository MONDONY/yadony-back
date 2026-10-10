package com.yadony.api.disputes;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.dto.AdminOpenDisputeRequest;
import com.yadony.api.disputes.dto.AdminOpenDisputeResponse;
import com.yadony.api.disputes.events.DisputeOpenedEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ouverture d'un litige par l'administration, au nom de l'expéditeur ou du voyageur.
 *
 * <p>Réutilise le flux existant : le litige est enregistré ici (type {@code ADMIN_<motif>},
 * statut OPEN, remboursement gelé), puis {@link DisputeOpenedEvent} est publié — le listener
 * du package retrouve le litige par (bid, type) sans le dupliquer, et la notification
 * « Litige ouvert » part aux deux parties. Le versement au voyageur est gelé par la garde de
 * {@code payments.DeliveryEventListener} (et de la libération forcée) tant que le litige est
 * ouvert.
 *
 * <p>Refus : colis sans transaction engagée (409 {@code bid-not-disputable}), litige déjà
 * ouvert (409 {@code dispute-already-open}), voyageur déjà payé (409
 * {@code payment-already-released}, rien ne peut plus être gelé).
 */
@Service
public class AdminDisputeOpeningService {

    /** Colis engagés : paiement en séquestre ou colis accepté, quelle que soit la suite. */
    static final Set<BidStatus> DISPUTABLE_STATUSES = EnumSet.of(
            BidStatus.PAYMENT_ESCROWED, BidStatus.ACCEPTED, BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT,
            BidStatus.ARRIVED, BidStatus.COMPLETED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED);

    private final DisputeRepository disputeRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    public AdminDisputeOpeningService(DisputeRepository disputeRepository, BidRepository bidRepository,
                                      AnnouncementRepository announcementRepository,
                                      PaymentRepository paymentRepository, AuditService auditService,
                                      ApplicationEventPublisher eventPublisher) {
        this.disputeRepository = disputeRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AdminOpenDisputeResponse open(UUID bidId, UUID adminId, AdminOpenDisputeRequest request) {
        bidRepository.lockForUpdate(bidId);
        BidEntity bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND, "bid-not-found",
                        "Not Found", "Colis introuvable"));

        if (!DISPUTABLE_STATUSES.contains(bid.getStatus())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "bid-not-disputable", "Bid Not Disputable",
                    "Ce colis n'a pas de transaction engagée (statut " + bid.getStatus()
                            + ") : aucun litige ne peut être ouvert.");
        }
        if (disputeRepository.existsByBidIdAndStatusNot(bidId, DisputeTypes.STATUS_RESOLVED)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "dispute-already-open", "Dispute Already Open",
                    "Un litige est déjà ouvert sur ce colis : suivez-le dans Incidents.");
        }

        Optional<PaymentEntity> payment = paymentRepository.findForBid(bidId);
        // Verrou de la ligne du paiement AVANT la décision : un versement concurrent (claim
        // ESCROW → RELEASED) attend notre commit puis voit le litige, ou l'a déjà posé et le
        // statut relu en base ci-dessous le montre. Jamais de décision sur une lecture en cache.
        PaymentStatus paymentStatusNow = null;
        if (payment.isPresent()) {
            paymentRepository.lockIfEscrow(payment.get().getId());
            paymentStatusNow = paymentRepository.findStatusById(payment.get().getId()).orElse(payment.get().getStatus());
        }
        if (paymentStatusNow == PaymentStatus.RELEASED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "payment-already-released",
                    "Payment Already Released",
                    "Le voyageur a déjà été payé pour ce colis : un litige ne peut plus geler l'argent. "
                            + "Traitez le différend avec le support ou par un remboursement manuel.");
        }

        String type = DisputeTypes.adminType(request.reason());
        if (disputeRepository.findByBidIdAndType(bidId, type).isPresent()) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "dispute-already-exists",
                    "Dispute Already Exists",
                    "Un litige pour ce motif a déjà été traité sur ce colis : choisissez un autre motif.");
        }

        AnnouncementEntity announcement = bid.getAnnouncementId() != null
                ? announcementRepository.findById(bid.getAnnouncementId()).orElse(null) : null;
        UUID travelerId = announcement != null ? announcement.getTravelerId() : null;
        if (travelerId == null) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "traveler-not-found", "Traveler Not Found",
                    "Le trajet de ce colis n'existe plus : le voyageur est introuvable.");
        }
        UUID reporterId = request.openedOnBehalfOf() == DisputeParty.SENDER ? bid.getSenderId() : travelerId;

        DisputeEntity dispute = new DisputeEntity();
        dispute.setBidId(bidId);
        dispute.setSenderId(bid.getSenderId());
        dispute.setTravelerId(travelerId);
        dispute.setType(type);
        dispute.setStatus(DisputeTypes.STATUS_OPEN);
        dispute.setRefundFrozen(true);
        dispute.setReporterId(reporterId);
        dispute.setReason(request.description().trim());
        DisputeEntity saved = disputeRepository.save(dispute);

        String paymentStatus = paymentStatusNow != null ? paymentStatusNow.name() : null;
        boolean payoutFrozen = paymentStatusNow == PaymentStatus.ESCROW;

        Map<String, Object> payload = new HashMap<>();
        payload.put("bidId", bidId.toString());
        payload.put("type", type);
        payload.put("reason", request.reason().name());
        payload.put("openedOnBehalfOf", request.openedOnBehalfOf().name());
        payload.put("description", request.description().trim());
        payload.put("paymentStatus", String.valueOf(paymentStatus));
        payload.put("payoutFrozen", payoutFrozen);
        auditService.log("DISPUTE", saved.getId(), "ADMIN_DISPUTE_OPENED", adminId, payload);

        eventPublisher.publishEvent(new DisputeOpenedEvent(bidId, bid.getSenderId(), travelerId, type));

        return new AdminOpenDisputeResponse(saved.getId(), bidId, type, saved.getStatus(),
                request.openedOnBehalfOf().name(), payoutFrozen, paymentStatus);
    }
}
