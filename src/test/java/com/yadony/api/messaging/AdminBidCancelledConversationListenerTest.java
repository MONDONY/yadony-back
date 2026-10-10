package com.yadony.api.messaging;

import com.yadony.api.cancellation.events.AdminBidCancelledEvent;
import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminBidCancelledConversationListenerTest {

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;

    private final UUID bidId = UUID.randomUUID();

    private AdminBidCancelledEvent event(boolean refund, boolean withTraveler) {
        return new AdminBidCancelledEvent(bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), refund, refund ? new BigDecimal("42.00") : BigDecimal.ZERO, "EUR", withTraveler);
    }

    private AdminBidCancelledConversationListener listener(AppLanguage lang) {
        return new AdminBidCancelledConversationListener(conversationRepository, firestoreService,
                TestMessages.resolver(lang));
    }

    @Test
    void conversationConservee_messageSystemeRemboursement() {
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(
                new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv-1")));

        listener(AppLanguage.FR).handleAdminBidCancelled(event(true, false));

        verify(firestoreService).addSystemMessage(eq("conv-1"), eq("L'équipe Yadony a annulé ce colis. "
                + "L'expéditeur est remboursé intégralement, aucun versement n'est fait au voyageur."));
    }

    @Test
    void colisChezLeVoyageur_messageRetour_enAnglais() {
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(
                new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv-1")));

        listener(AppLanguage.EN).handleAdminBidCancelled(event(true, true));

        verify(firestoreService).addSystemMessage(eq("conv-1"), eq("The Yadony team cancelled this parcel. "
                + "Support is contacting the sender and the traveler to arrange its return."));
    }

    @Test
    void sansRemboursement_messageSimple() {
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(
                new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv-1")));

        listener(AppLanguage.FR).handleAdminBidCancelled(event(false, false));

        verify(firestoreService).addSystemMessage("conv-1", "L'équipe Yadony a annulé ce colis.");
    }

    @Test
    void sansConversation_rien_etEchecFirestoreAvale() {
        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        listener(AppLanguage.FR).handleAdminBidCancelled(event(false, false));
        verifyNoInteractions(firestoreService);

        when(conversationRepository.findByBidId(bidId)).thenReturn(Optional.of(
                new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv-1")));
        doThrow(new RuntimeException("firestore down")).when(firestoreService).addSystemMessage(any(), any());
        assertThatNoException().isThrownBy(() -> listener(AppLanguage.FR).handleAdminBidCancelled(event(true, false)));
    }
}
