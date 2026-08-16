package com.yadony.api.messaging;

import com.yadony.api.matching.events.TripArrivedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripArrivedConversationListenerTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;

    TripArrivedConversationListener listener;

    @BeforeEach
    void setUp() {
        listener = new TripArrivedConversationListener(conversationRepository, firestoreService);
    }

    @Test
    void handleTripArrived_postsSystemMessagePerBid() {
        UUID bidId = UUID.randomUUID();
        ConversationEntity conv = new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv-123");
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(conv));

        listener.handleTripArrived(new TripArrivedEvent(UUID.randomUUID(),
                List.of(new TripArrivedEvent.BidTarget(bidId, UUID.randomUUID()))));

        verify(firestoreService).addSystemMessage(eq("conv-123"), anyString());
    }

    @Test
    void handleTripArrived_noConversation_doesNothing() {
        UUID bidId = UUID.randomUUID();
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        listener.handleTripArrived(new TripArrivedEvent(UUID.randomUUID(),
                List.of(new TripArrivedEvent.BidTarget(bidId, UUID.randomUUID()))));

        verify(firestoreService, never()).addSystemMessage(any(), any());
    }
}
