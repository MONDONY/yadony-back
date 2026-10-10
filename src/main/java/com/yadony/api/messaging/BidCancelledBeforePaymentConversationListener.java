package com.yadony.api.messaging;

import com.yadony.api.cancellation.events.BidCancelledBeforePaymentEvent;
import com.yadony.api.common.i18n.MessagesResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Trace l'annulation avant paiement dans la conversation expéditeur ↔ voyageur du colis, quand
 * elle existe (le voyageur avait accepté). La conversation n'est ni retirée, ni archivée, ni
 * verrouillée ici : c'est l'utilisateur qui décide de la garder ou non. Langue de l'expéditeur,
 * auteur de l'annulation, comme {@link TripRescheduledConversationListener}.
 */
@Component
public class BidCancelledBeforePaymentConversationListener {

    private static final Logger log = LoggerFactory.getLogger(BidCancelledBeforePaymentConversationListener.class);

    static final String MESSAGE_KEY = "conversation.bid-cancelled-before-payment";

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final MessagesResolver messagesResolver;

    public BidCancelledBeforePaymentConversationListener(ConversationRepository conversationRepository,
                                                         FirestoreService firestoreService,
                                                         MessagesResolver messagesResolver) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.messagesResolver = messagesResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onBidCancelledBeforePayment(BidCancelledBeforePaymentEvent event) {
        try {
            conversationRepository.findByBidId(event.bidId()).ifPresent(conv ->
                    firestoreService.addSystemMessage(conv.getFirestoreConversationId(),
                            messagesResolver.forUser(event.senderId()).get(MESSAGE_KEY)));
        } catch (Exception e) {
            log.error("Message système d'annulation avant paiement non posté pour le bid {} : {}",
                    event.bidId(), e.getMessage());
        }
    }
}
