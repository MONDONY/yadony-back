package com.yadony.api.messaging;

import com.yadony.api.matching.BidNegotiationMessageKind;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.events.BidCreatedEvent;
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
 * Aucune exception ne remonte au bus : la promotion est un bonus de la création du bid.
 */
@Component
public class InquiryPromotionListener {

    private static final Logger log = LoggerFactory.getLogger(InquiryPromotionListener.class);

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
