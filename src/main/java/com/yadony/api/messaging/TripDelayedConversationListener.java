package com.yadony.api.messaging;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.matching.events.TripDelayedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;

/**
 * Trace le retard annoncé par le voyageur dans la conversation de chaque colis en route, pour que
 * l'information reste lisible là où expéditeur et voyageur s'organisent. Langue de l'expéditeur. Le
 * message libre du voyageur, s'il y en a un, suit la phrase.
 */
@Component
public class TripDelayedConversationListener {

    private static final Logger log = LoggerFactory.getLogger(TripDelayedConversationListener.class);

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final MessagesResolver messagesResolver;

    public TripDelayedConversationListener(ConversationRepository conversationRepository,
                                           FirestoreService firestoreService,
                                           MessagesResolver messagesResolver) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.messagesResolver = messagesResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleTripDelayed(TripDelayedEvent event) {
        for (TripDelayedEvent.Target target : event.targets()) {
            try {
                conversationRepository.findByBidId(target.bidId()).ifPresent(conv ->
                        firestoreService.addSystemMessage(conv.getFirestoreConversationId(),
                                body(messagesResolver.forUser(target.senderId()), event)));
            } catch (Exception e) {
                log.error("Failed to post trip-delayed system message for bid {}: {}",
                        target.bidId(), e.getMessage());
            }
        }
    }

    static String body(Messages m, TripDelayedEvent event) {
        DateTimeFormatter pattern = DateTimeFormatter.ofPattern(
                m.get("conversation.trip-delayed.date-pattern"), m.locale());
        String reason = m.get("trip-delay.reason." + event.reason());
        String from = event.arrivalDate().format(pattern);
        String sentence = event.arrivalDate().equals(event.arrivalDateMax())
                ? m.get("conversation.trip-delayed-single", reason, from)
                : m.get("conversation.trip-delayed", reason, from, event.arrivalDateMax().format(pattern));
        if (event.message() == null || event.message().isBlank()) {
            return sentence;
        }
        return sentence + " " + m.get("conversation.trip-delayed.message", event.message());
    }
}
