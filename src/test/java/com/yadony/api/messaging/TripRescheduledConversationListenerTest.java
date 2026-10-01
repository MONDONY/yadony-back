package com.yadony.api.messaging;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.events.TripRescheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripRescheduledConversationListenerTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;

    TripRescheduledConversationListener listener;

    @BeforeEach
    void setUp() {
        listener = new TripRescheduledConversationListener(conversationRepository, firestoreService,
                TestMessages.resolver());
    }

    private TripRescheduledEvent event(UUID bidA, UUID bidB) {
        return new TripRescheduledEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "POSTPONED",
                LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 13), LocalTime.of(22, 0),
                List.of(new TripRescheduledEvent.Target(bidA, UUID.randomUUID(), true),
                        new TripRescheduledEvent.Target(bidB, UUID.randomUUID(), false)));
    }

    @Test
    void postsTheRescheduleInEachParcelConversation() {
        UUID bidA = UUID.randomUUID();
        UUID bidB = UUID.randomUUID();
        when(conversationRepository.findByBidId(bidA)).thenReturn(Optional.of(
                new ConversationEntity(bidA, UUID.randomUUID(), UUID.randomUUID(), "conv-a")));
        when(conversationRepository.findByBidId(bidB)).thenReturn(Optional.empty());

        listener.handleTripRescheduled(event(bidA, bidB));

        verify(firestoreService).addSystemMessage(eq("conv-a"),
                eq("Le voyageur a reporté le trajet au mardi 13 octobre (voyage repoussé). L'expéditeur peut garder "
                        + "le colis sur la nouvelle date ou annuler sans frais depuis le suivi du colis."));
    }

    @Test
    void aFirestoreFailureDoesNotStopTheOtherConversations() {
        UUID bidA = UUID.randomUUID();
        UUID bidB = UUID.randomUUID();
        when(conversationRepository.findByBidId(bidA)).thenReturn(Optional.of(
                new ConversationEntity(bidA, UUID.randomUUID(), UUID.randomUUID(), "conv-a")));
        when(conversationRepository.findByBidId(bidB)).thenReturn(Optional.of(
                new ConversationEntity(bidB, UUID.randomUUID(), UUID.randomUUID(), "conv-b")));
        doThrow(new RuntimeException("down")).when(firestoreService).addSystemMessage(eq("conv-a"), any());

        listener.handleTripRescheduled(event(bidA, bidB));

        verify(firestoreService).addSystemMessage(eq("conv-b"),
                eq("Le voyageur a reporté le trajet au mardi 13 octobre (voyage repoussé)."));
    }
}
