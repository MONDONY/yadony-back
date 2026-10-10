package com.yadony.api.messaging;

import com.yadony.api.cancellation.events.BidCancelledBeforePaymentEvent;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidCancelledBeforePaymentConversationListenerTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;

    BidCancelledBeforePaymentConversationListener listener;

    @BeforeEach
    void setUp() {
        listener = new BidCancelledBeforePaymentConversationListener(conversationRepository, firestoreService,
                TestMessages.resolver());
    }

    private static BidCancelledBeforePaymentEvent event(UUID bidId) {
        return new BidCancelledBeforePaymentEvent(bidId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "MOBILE_MONEY", true, null);
    }

    @Test
    void postsASystemMessageInTheExistingConversation() {
        UUID bidId = UUID.randomUUID();
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(
                new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv-1")));

        listener.onBidCancelledBeforePayment(event(bidId));

        verify(firestoreService).addSystemMessage(eq("conv-1"),
                eq("L'expéditeur a annulé la demande avant le paiement. Aucun montant n'a été débité."));
    }

    @Test
    void withoutConversation_postsNothing() {
        UUID bidId = UUID.randomUUID();
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        listener.onBidCancelledBeforePayment(event(bidId));

        verify(firestoreService, never()).addSystemMessage(any(), any());
    }

    @Test
    void aFirestoreFailureIsSwallowed() {
        UUID bidId = UUID.randomUUID();
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(
                new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv-2")));
        doThrow(new IllegalStateException("firestore down")).when(firestoreService).addSystemMessage(any(), any());

        assertThatCode(() -> listener.onBidCancelledBeforePayment(event(bidId))).doesNotThrowAnyException();
    }
}
