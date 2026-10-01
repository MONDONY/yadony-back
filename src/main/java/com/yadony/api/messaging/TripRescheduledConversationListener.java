package com.yadony.api.messaging;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.matching.events.TripRescheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;

/**
 * Trace le report du trajet dans la conversation de chaque colis touché, pour que
 * l'information reste lisible là où expéditeur et voyageur s'organisent. Langue de
 * l'expéditeur, à qui la décision revient.
 */
@Component
public class TripRescheduledConversationListener {

    private static final Logger log = LoggerFactory.getLogger(TripRescheduledConversationListener.class);

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;
    private final MessagesResolver messagesResolver;

    public TripRescheduledConversationListener(ConversationRepository conversationRepository,
                                               FirestoreService firestoreService,
                                               MessagesResolver messagesResolver) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
        this.messagesResolver = messagesResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleTripRescheduled(TripRescheduledEvent event) {
        for (TripRescheduledEvent.Target target : event.targets()) {
            try {
                conversationRepository.findByBidId(target.bidId()).ifPresent(conv ->
                        firestoreService.addSystemMessage(conv.getFirestoreConversationId(),
                                body(messagesResolver.forUser(target.senderId()), event, target)));
            } catch (Exception e) {
                log.error("Failed to post trip-rescheduled system message for bid {}: {}",
                        target.bidId(), e.getMessage());
            }
        }
    }

    static String body(Messages m, TripRescheduledEvent event, TripRescheduledEvent.Target target) {
        String date = event.newDepartureDate().format(DateTimeFormatter.ofPattern(
                m.get("notification.trip-rescheduled.date-pattern"), m.locale()));
        String reason = m.get("trip-reschedule.reason." + event.reason());
        return m.get(target.decisionRequired() ? "conversation.trip-rescheduled-decision"
                : "conversation.trip-rescheduled", date, reason);
    }
}
