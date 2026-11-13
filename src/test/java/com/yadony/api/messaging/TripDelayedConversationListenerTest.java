package com.yadony.api.messaging;

import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.events.TripDelayedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripDelayedConversationListenerTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;

    private final LocalDate from = LocalDate.of(2026, 10, 13);
    private final LocalDate to = LocalDate.of(2026, 10, 20);

    private TripDelayedConversationListener listener(AppLanguage language) {
        return new TripDelayedConversationListener(conversationRepository, firestoreService,
                TestMessages.resolver(language));
    }

    private TripDelayedEvent event(UUID bidA, UUID bidB, LocalDate max, String message) {
        return new TripDelayedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PORT_CONGESTION",
                from, max, message,
                List.of(new TripDelayedEvent.Target(bidA, UUID.randomUUID()),
                        new TripDelayedEvent.Target(bidB, UUID.randomUUID())));
    }

    @Test
    void postsTheDelayInEachParcelConversation_withTheTravelersMessage() {
        UUID bidA = UUID.randomUUID();
        UUID bidB = UUID.randomUUID();
        when(conversationRepository.findByBidId(bidA)).thenReturn(Optional.of(
                new ConversationEntity(bidA, UUID.randomUUID(), UUID.randomUUID(), "conv-a")));
        when(conversationRepository.findByBidId(bidB)).thenReturn(Optional.empty());

        listener(AppLanguage.FR).handleTripDelayed(event(bidA, bidB, to, "Quai saturé"));

        verify(firestoreService).addSystemMessage(eq("conv-a"),
                eq("Le voyageur a annoncé un retard (port encombré) : nouvelle arrivée estimée du mardi 13 octobre "
                        + "au mardi 20 octobre. Message du voyageur : Quai saturé"));
        verify(firestoreService, never()).addSystemMessage(eq("conv-b"), any());
    }

    @Test
    void inEnglish_singleDay_noMessage() {
        UUID bidA = UUID.randomUUID();
        when(conversationRepository.findByBidId(bidA)).thenReturn(Optional.of(
                new ConversationEntity(bidA, UUID.randomUUID(), UUID.randomUUID(), "conv-a")));

        TripDelayedEvent single = new TripDelayedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "EXTENDED_STOPOVER", from, from, "  ",
                List.of(new TripDelayedEvent.Target(bidA, UUID.randomUUID())));
        listener(AppLanguage.EN).handleTripDelayed(single);

        verify(firestoreService).addSystemMessage(eq("conv-a"),
                eq("The traveler announced a delay (extended stopover): new estimated arrival Tuesday, October 13."));
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

        listener(AppLanguage.FR).handleTripDelayed(event(bidA, bidB, to, null));

        verify(firestoreService).addSystemMessage(eq("conv-b"), any());
    }

    @Test
    void everyReasonHasAWording_frAndEn() {
        for (String reason : List.of("PORT_CONGESTION", "WEATHER", "EXTENDED_STOPOVER", "OTHER")) {
            TripDelayedEvent e = new TripDelayedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    reason, from, to, null, List.of());
            assertThat(TripDelayedConversationListener.body(TestMessages.fr(), e)).doesNotContain("trip-delay.reason");
            assertThat(TripDelayedConversationListener.body(TestMessages.en(), e)).doesNotContain("trip-delay.reason");
        }
    }
}
