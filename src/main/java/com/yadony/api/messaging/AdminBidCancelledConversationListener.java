package com.yadony.api.messaging;

import com.yadony.api.cancellation.events.AdminBidCancelledEvent;
import com.yadony.api.common.i18n.MessagesResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Colis annulé par l'administration : la conversation est conservée (jamais retirée, c'est
 * l'utilisateur qui décide) et un message système y explique ce qui s'est passé. Langue de
 * l'expéditeur, comme les autres messages système du colis.
 */
@Component
public class AdminBidCancelledConversationListener {

    private static final Logger log = LoggerFactory.getLogger(AdminBidCancelledConversationListener.class);

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final MessagesResolver messagesResolver;

    public AdminBidCancelledConversationListener(ConversationRepository conversationRepository,
                                                 FirestoreService firestoreService,
                                                 MessagesResolver messagesResolver) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.messagesResolver = messagesResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleAdminBidCancelled(AdminBidCancelledEvent event) {
        try {
            conversationRepository.findByBidId(event.bidId()).ifPresent(conv ->
                    firestoreService.addSystemMessage(conv.getFirestoreConversationId(),
                            messagesResolver.forUser(event.senderId()).get(key(event))));
        } catch (Exception e) {
            log.error("Failed to post admin-cancellation system message for bid {}: {}",
                    event.bidId(), e.getMessage());
        }
    }

    static String key(AdminBidCancelledEvent event) {
        if (event.parcelWithTraveler()) return "conversation.bid-cancelled-by-admin-return";
        if (event.refundRequested()) return "conversation.bid-cancelled-by-admin-refund";
        return "conversation.bid-cancelled-by-admin";
    }
}
