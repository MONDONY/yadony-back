package com.yadony.api.messaging;

import com.yadony.api.matching.events.TripArrivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TripArrivedConversationListener {

    private static final Logger log = LoggerFactory.getLogger(TripArrivedConversationListener.class);

    private final ConversationRepository conversationRepository;
    private final FirestoreService firestoreService;

    public TripArrivedConversationListener(ConversationRepository conversationRepository,
                                            FirestoreService firestoreService) {
        this.conversationRepository = conversationRepository;
        this.firestoreService = firestoreService;
    }

    @EventListener
    @Async
    public void handleTripArrived(TripArrivedEvent event) {
        for (TripArrivedEvent.BidTarget target : event.getTargets()) {
            try {
                Optional<ConversationEntity> conv = conversationRepository.findByBidId(target.bidId());
                if (conv.isPresent()) {
                    firestoreService.addSystemMessage(
                            conv.get().getFirestoreConversationId(),
                            "Votre voyageur est arrivé à destination. Consultez les instructions de retrait dans le suivi.");
                }
            } catch (Exception e) {
                log.error("Failed to post trip-arrived system message for bid {}: {}", target.bidId(), e.getMessage());
            }
        }
    }
}
