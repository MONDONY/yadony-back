package com.yadony.api.messaging;

import com.yadony.api.matching.BidNegotiationMessageKind;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidAwaitingPaymentAbandonedEvent;
import com.yadony.api.matching.events.BidCancelledByParticipantEvent;
import com.yadony.api.matching.events.BidCardAuthorizationExpiredEvent;
import com.yadony.api.matching.events.BidCreatedEvent;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidNegotiationExpiredEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.BidMaterializedEvent;
import com.yadony.api.matching.events.BidNegotiationMessagePostedEvent;
import com.yadony.api.matching.events.CashBidCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Rattache la conversation de questions d'un expéditeur à la demande qu'il vient de créer
 * (le plafond de messages tombe à la demande, pas à l'acceptation). Une écoute par chemin de
 * création d'un bid, toutes idempotentes (un fil déjà promu n'est jamais touché) :
 * <ul>
 *   <li>{@link BidCreatedEvent} : parcours carte, publié à l'autorisation du paiement ;</li>
 *   <li>{@link CashBidCreatedEvent} : demande espèces / mobile money à la création, et accord
 *       de prix négocié en espèces ;</li>
 *   <li>{@link BidNegotiationMessagePostedEvent} {@code PROPOSAL} du tour 1 : la proposition
 *       de prix crée le bid {@code NEGOTIATING} sans autre événement de création ;</li>
 *   <li>{@link BidMaterializedEvent} : bid issu d'un fil de négociation de demande de colis
 *       (l'événement ne porte ni expéditeur ni annonce, relus depuis le bid).</li>
 * </ul>
 * <p>Inversement, quand la demande se termine SANS jamais avoir été acceptée, la conversation
 * redevient une conversation de questions ({@link ConversationService#relockInquiry}) :
 * <ul>
 *   <li>{@link BidRejectedEvent} et {@link BidCancelledByParticipantEvent} : seulement si le
 *       statut d'avant la transition, porté par l'événement, précède l'acceptation
 *       ({@link #NEVER_ACCEPTED_STATUSES}, AWAITING_PAYMENT seulement pour un paiement carte et
 *       seulement via {@link BidCancelledByParticipantEvent}) ; un statut absent n'est jamais deviné ;</li>
 *   <li>{@link BidNegotiationExpiredEvent} (fil de prix éteint, jamais une réservation),
 *       {@link BidExpiredOnDepartureEvent} (n'expire que PENDING, PAYMENT_ESCROWED ou
 *       NEGOTIATING), {@link BidCardAuthorizationExpiredEvent} (seulement depuis
 *       PAYMENT_ESCROWED) et {@link BidAwaitingPaymentAbandonedEvent} (seulement depuis
 *       AWAITING_PAYMENT) : antérieurs à l'acceptation par construction de leur publicateur.</li>
 * </ul>
 * Aucune exception ne remonte au bus : la promotion et le reverrouillage sont des bonus.
 */
@Component
public class InquiryPromotionListener {

    private static final Logger log = LoggerFactory.getLogger(InquiryPromotionListener.class);

    /**
     * Statuts d'avant la transition pour lesquels le bid n'a jamais été accepté, quel que soit le
     * moyen de paiement. AWAITING_PAYMENT en est EXCLU : en mobile money il vient APRÈS
     * l'acceptation du voyageur (cf. {@link #neverAccepted}).
     */
    static final Set<BidStatus> NEVER_ACCEPTED_STATUSES = EnumSet.of(
            BidStatus.PENDING, BidStatus.PAYMENT_ESCROWED, BidStatus.NEGOTIATING);

    private final ConversationService conversationService;
    private final BidRepository bidRepository;

    public InquiryPromotionListener(ConversationService conversationService, BidRepository bidRepository) {
        this.conversationService = conversationService;
        this.bidRepository = bidRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidCreated(BidCreatedEvent event) {
        promote(event.getSenderId(), event.getAnnouncementId(), event.getBidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCashBidCreated(CashBidCreatedEvent event) {
        promote(event.senderId(), event.announcementId(), event.bidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidMaterialized(BidMaterializedEvent event) {
        promoteFromBid(event.getBidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNegotiationMessage(BidNegotiationMessagePostedEvent event) {
        if (event.kind() == BidNegotiationMessageKind.PROPOSAL && event.round() == 1) {
            promoteFromBid(event.bidId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidRejected(BidRejectedEvent event) {
        // Pas d'AWAITING_PAYMENT ici : l'événement ne porte pas le moyen de paiement. Une
        // annulation depuis ce statut passe aussi par BidCancelledByParticipantEvent, qui le porte.
        if (event.getPreviousStatus() != null && NEVER_ACCEPTED_STATUSES.contains(event.getPreviousStatus())) {
            relock(event.getBidId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidCancelledByParticipant(BidCancelledByParticipantEvent event) {
        if (neverAccepted(event)) {
            relock(event.bidId());
        }
    }

    /**
     * AWAITING_PAYMENT précède l'acceptation en carte (paiement à faire avant la demande) mais la
     * SUIT en mobile money (le voyageur a accepté, l'expéditeur doit payer) : il ne compte donc
     * comme « jamais acceptée » que pour un paiement carte connu. Moyen inconnu : jamais deviné.
     */
    private static boolean neverAccepted(BidCancelledByParticipantEvent event) {
        BidStatus before = event.previousStatus();
        if (before == BidStatus.AWAITING_PAYMENT) {
            return event.paymentMethod() != null && event.paymentMethod().isCardEscrow();
        }
        return before != null && NEVER_ACCEPTED_STATUSES.contains(before);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNegotiationExpired(BidNegotiationExpiredEvent event) {
        relock(event.bidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onExpiredOnDeparture(BidExpiredOnDepartureEvent event) {
        relock(event.getBidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCardAuthorizationExpired(BidCardAuthorizationExpiredEvent event) {
        relock(event.bidId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAwaitingPaymentAbandoned(BidAwaitingPaymentAbandonedEvent event) {
        relock(event.bidId());
    }

    private void relock(UUID bidId) {
        try {
            if (conversationService.relockInquiry(bidId)) {
                log.info("Conversation de réservation du bid {} revenue en conversation de questions", bidId);
            }
        } catch (Exception e) {
            log.error("Reverrouillage de la conversation impossible pour le bid {} : {}", bidId, e.getMessage());
        }
    }

    private void promoteFromBid(UUID bidId) {
        try {
            bidRepository.findById(bidId).ifPresent(bid ->
                    promote(bid.getSenderId(), bid.getAnnouncementId(), bidId));
        } catch (Exception e) {
            log.error("Promotion de la conversation de questions impossible pour le bid {} : {}",
                    bidId, e.getMessage());
        }
    }

    private void promote(UUID senderId, UUID announcementId, UUID bidId) {
        try {
            if (conversationService.promoteInquiryToBid(senderId, announcementId, bidId)) {
                log.info("Conversation de questions rattachée au bid {}", bidId);
            }
        } catch (Exception e) {
            log.error("Promotion de la conversation de questions impossible pour le bid {} : {}",
                    bidId, e.getMessage());
        }
    }
}
