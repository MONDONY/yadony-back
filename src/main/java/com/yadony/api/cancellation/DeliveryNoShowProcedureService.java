package com.yadony.api.cancellation;

import com.yadony.api.calls.CallRepository;
import com.yadony.api.cancellation.dto.DeliveryNoShowProcedureResponse;
import com.yadony.api.cancellation.events.DeliveryRetryAppointmentSetEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.messaging.ConversationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Procédure encadrée « destinataire absent à l'arrivée » (FLUTTER-E2).
 *
 * <ol>
 *   <li><b>Avant le signalement</b> : le colis doit être déclaré arrivé (ARRIVED), le délai
 *       d'attente minimal écoulé depuis l'arrivée déclarée ({@code bids.arrived_at}), et une
 *       tentative de contact prouvée côté serveur : un appel in-app lancé par le voyageur sur
 *       ce colis ({@code calls}), ou un message du voyageur dans une conversation du colis
 *       ({@code conversations.traveler_last_message_at}). Le voyageur coche en plus une case
 *       de confirmation (attente sur place, tentatives hors app que le serveur ne voit pas).</li>
 *   <li><b>Au signalement</b> : la garde du colis commence ({@code hold_until} = signalement +
 *       {@code holdDays}). L'expéditeur, prévenu, peut fixer un nouveau rendez-vous
 *       ({@link #setRetryAppointment}) ou changer de destinataire (flux existant
 *       {@code PUT /bids/{id}/recipient}), ou contester dans le délai habituel (litige).</li>
 *   <li><b>Garde échue</b> sans livraison ni litige ouvert : le colis passe « non réclamé »
 *       ({@link UnclaimedParcelScheduler}) et le net est libéré au voyageur.</li>
 * </ol>
 *
 * <p>Aucun statut de bid, de paiement ni de déclaration n'est ajouté : la procédure vit sur la
 * ligne {@code cancellations} de portée DELIVERY qui porte déjà le signalement.
 */
@Service
public class DeliveryNoShowProcedureService {

    static final String PROOF_CALL = "CALL";
    static final String PROOF_MESSAGE = "MESSAGE";

    private final CancellationRepository cancellationRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final CallRepository callRepository;
    private final ConversationRepository conversationRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final DeliveryNoShowProperties properties;
    private final Clock clock;

    @Autowired
    public DeliveryNoShowProcedureService(CancellationRepository cancellationRepository,
                                          BidRepository bidRepository,
                                          AnnouncementRepository announcementRepository,
                                          CallRepository callRepository,
                                          ConversationRepository conversationRepository,
                                          AuditService auditService,
                                          ApplicationEventPublisher eventPublisher,
                                          DeliveryNoShowProperties properties) {
        this(cancellationRepository, bidRepository, announcementRepository, callRepository,
                conversationRepository, auditService, eventPublisher, properties, Clock.systemUTC());
    }

    DeliveryNoShowProcedureService(CancellationRepository cancellationRepository,
                                   BidRepository bidRepository,
                                   AnnouncementRepository announcementRepository,
                                   CallRepository callRepository,
                                   ConversationRepository conversationRepository,
                                   AuditService auditService,
                                   ApplicationEventPublisher eventPublisher,
                                   DeliveryNoShowProperties properties,
                                   Clock clock) {
        this.cancellationRepository = cancellationRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.callRepository = callRepository;
        this.conversationRepository = conversationRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /** Conditions préalables vérifiées, prêtes à être posées sur la déclaration. */
    public record ReportPreconditions(String contactProof, OffsetDateTime confirmedAt, OffsetDateTime holdUntil) {
    }

    /** Ce que le serveur sait de l'attente et du contact, sans rien lever. */
    record Eligibility(LocalDateTime since, OffsetDateTime availableAt, boolean waitElapsed, String contactProof) {
    }

    /**
     * Vérifie les préalables du signalement par le voyageur (appelé par
     * {@link CancellationService#reportDeliveryNoShow}, après les gardes historiques de statut
     * et de propriété). Lève une erreur RFC 7807 explicite pour chaque condition manquante.
     */
    public ReportPreconditions checkReportPreconditions(BidEntity bid, UUID travelerId, boolean contactConfirmed) {
        if (bid.getStatus() != BidStatus.ARRIVED) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "delivery-noshow-arrival-not-declared",
                    "Arrival Not Declared",
                    "Déclarez d'abord votre arrivée à destination avant de signaler le destinataire absent.");
        }
        Eligibility e = evaluate(bid, travelerId);
        if (!e.waitElapsed()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "delivery-noshow-wait-not-elapsed",
                    "Waiting Time Not Elapsed",
                    "Attendez le destinataire au moins " + properties.minWaitMinutes()
                            + " minutes après votre arrivée avant de le signaler absent.",
                    Map.of("availableAt", e.availableAt().toString(),
                            "minWaitMinutes", properties.minWaitMinutes()));
        }
        if (e.contactProof() == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "delivery-noshow-contact-required",
                    "Contact Attempt Required",
                    "Appelez ou écrivez au destinataire ou à l'expéditeur depuis l'app avant de signaler l'absence.");
        }
        if (!contactConfirmed) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "delivery-noshow-confirmation-required", "Confirmation Required",
                    "Confirmez avoir attendu sur place et tenté de joindre le destinataire.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new ReportPreconditions(e.contactProof(), now, now.plusDays(properties.holdDays()));
    }

    Eligibility evaluate(BidEntity bid, UUID travelerId) {
        // Repère de l'attente : l'arrivée déclarée. Un colis arrivé avant V301 n'a pas
        // d'arrived_at : on retombe sur la dernière modification du bid, qui lui est postérieure
        // ou égale (repère plus tardif, donc plus prudent).
        LocalDateTime since = bid.getArrivedAt() != null ? bid.getArrivedAt() : bid.getUpdatedAt();
        if (since == null) {
            return new Eligibility(null, null, false, null);
        }
        OffsetDateTime availableAt = since.atOffset(ZoneOffset.UTC).plusMinutes(properties.minWaitMinutes());
        boolean waitElapsed = !OffsetDateTime.now(clock).isBefore(availableAt);
        String proof = null;
        if (travelerId != null) {
            if (callRepository.existsByBidIdAndCallerIdAndCreatedAtGreaterThanEqual(bid.getId(), travelerId, since)) {
                proof = PROOF_CALL;
            } else if (conversationRepository.existsTravelerMessageSince(bid.getId(), since)) {
                proof = PROOF_MESSAGE;
            }
        }
        return new Eligibility(since, availableAt, waitElapsed, proof);
    }

    /** État de la procédure pour l'expéditeur ou le voyageur du colis. */
    @Transactional(readOnly = true)
    public DeliveryNoShowProcedureResponse getProcedure(UUID bidId, UUID callerId) {
        BidEntity bid = loadBid(bidId);
        AnnouncementEntity announcement = loadAnnouncement(bid);
        UUID travelerId = announcement.getTravelerId();
        boolean isSender = bid.getSenderId().equals(callerId);
        boolean isTraveler = travelerId != null && travelerId.equals(callerId);
        if (!isSender && !isTraveler) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes ni l'expéditeur ni le voyageur de ce colis.");
        }

        Optional<CancellationEntity> report = cancellationRepository
                .findByBidIdAndScope(bidId, CancellationScope.DELIVERY)
                .filter(c -> DeliveryNoShowTypes.isRecipientNoShow(c.getReason()));

        boolean arrived = bid.getStatus() == BidStatus.ARRIVED;
        Eligibility e = arrived ? evaluate(bid, travelerId) : new Eligibility(null, null, false, null);
        boolean anyDeliveryReport = cancellationRepository
                .findByBidIdAndScope(bidId, CancellationScope.DELIVERY).isPresent();
        boolean canReport = isTraveler && arrived && !anyDeliveryReport && e.waitElapsed() && e.contactProof() != null;

        CancellationEntity c = report.orElse(null);
        return new DeliveryNoShowProcedureResponse(
                bidId,
                isSender ? "SENDER" : "TRAVELER",
                bid.getStatus().name(),
                bid.getArrivedAt() != null ? bid.getArrivedAt().atOffset(ZoneOffset.UTC) : null,
                e.availableAt(),
                e.waitElapsed(),
                // La preuve n'intéresse que le voyageur : l'expéditeur n'a pas à savoir s'il a
                // été appelé ou écrit, il le voit dans sa conversation.
                isTraveler ? (c != null ? c.getContactProof() : e.contactProof()) : null,
                canReport,
                c != null,
                c != null ? c.getNoShowStatus().name() : null,
                c != null ? c.getContestationDeadline() : null,
                c != null ? c.getHoldUntil() : null,
                c != null ? c.getRetryAppointmentAt() : null,
                c != null ? c.getRetryAppointmentNote() : null,
                c != null ? c.getUnclaimedAt() : null,
                isSender && c != null && retryAppointmentOpen(c, bid),
                properties.minWaitMinutes(),
                properties.holdDays());
    }

    /**
     * L'expéditeur fixe (ou déplace) un nouveau rendez-vous de livraison pendant la garde. Le
     * rendez-vous doit tomber avant la fin de la garde : la garde n'est pas prolongée (valeur
     * prudente, à valider par le produit).
     */
    @Transactional
    public DeliveryNoShowProcedureResponse setRetryAppointment(UUID bidId, UUID senderId,
                                                               OffsetDateTime appointmentAt, String note) {
        BidEntity bid = loadBid(bidId);
        if (!bid.getSenderId().equals(senderId)) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Vous n'êtes pas l'expéditeur de ce colis.");
        }
        CancellationEntity c = cancellationRepository.findByBidIdAndScope(bidId, CancellationScope.DELIVERY)
                .filter(found -> DeliveryNoShowTypes.isRecipientNoShow(found.getReason()))
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "delivery-noshow-not-found", "Not Found",
                        "Aucun signalement de destinataire absent pour ce colis."));
        if (!retryAppointmentOpen(c, bid)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "retry-appointment-closed",
                    "Retry Appointment Closed",
                    "Le colis n'est plus en garde : un nouveau rendez-vous ne peut plus être fixé.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!appointmentAt.isAfter(now) || appointmentAt.isAfter(c.getHoldUntil())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "retry-appointment-out-of-hold",
                    "Appointment Outside Hold",
                    "Choisissez un rendez-vous à venir, avant la fin de la garde du colis.",
                    Map.of("holdUntil", c.getHoldUntil().toString()));
        }
        String trimmedNote = note == null || note.isBlank() ? null : note.trim();
        c.setRetryAppointmentAt(appointmentAt);
        c.setRetryAppointmentNote(trimmedNote);
        cancellationRepository.save(c);

        AnnouncementEntity announcement = loadAnnouncement(bid);
        Map<String, Object> payload = new HashMap<>();
        payload.put("bidId", bidId.toString());
        payload.put("appointmentAt", appointmentAt.toString());
        payload.put("holdUntil", c.getHoldUntil().toString());
        auditService.log("CANCELLATION", c.getId(), "DELIVERY_RETRY_APPOINTMENT_SET", senderId, payload);
        eventPublisher.publishEvent(new DeliveryRetryAppointmentSetEvent(
                bidId, senderId, announcement.getTravelerId(), appointmentAt));
        return getProcedure(bidId, senderId);
    }

    /** Garde en cours : signalement avec garde, ni contesté ni tranché, colis ni livré ni « non réclamé ». */
    private boolean retryAppointmentOpen(CancellationEntity c, BidEntity bid) {
        return c.getHoldUntil() != null
                && c.getUnclaimedAt() == null
                && (c.getNoShowStatus() == CancellationStatus.PENDING_CONFIRMATION
                        || c.getNoShowStatus() == CancellationStatus.CONFIRMED)
                && BidStatus.EN_ROUTE.contains(bid.getStatus())
                && OffsetDateTime.now(clock).isBefore(c.getHoldUntil());
    }

    private BidEntity loadBid(UUID bidId) {
        return bidRepository.findById(bidId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
    }

    private AnnouncementEntity loadAnnouncement(BidEntity bid) {
        return announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "announcement-not-found", "Not Found", "Annonce introuvable"));
    }
}
