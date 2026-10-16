package com.yadony.api.messaging;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** FLUTTER-QQ : à la confirmation de l'absence de l'expéditeur, la conversation passe en lecture seule. */
@ExtendWith(MockitoExtension.class)
class SenderNoShowConversationListenerTest {

    @Mock ConversationRepository conversationRepository;
    @Mock BidRepository bidRepository;
    @Mock FirestoreService firestoreService;

    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    private SenderNoShowConversationListener listener(AppLanguage lang) {
        return new SenderNoShowConversationListener(conversationRepository, bidRepository, firestoreService,
                TestMessages.resolver(lang));
    }

    private static CancellationConfirmedEvent event(UUID bidId, CancellationReason reason) {
        return new CancellationConfirmedEvent(bidId, UUID.randomUUID(), reason);
    }

    private BidEntity cancelledForSenderNoShow() {
        BidEntity bid = new BidEntity();
        bid.setSenderId(senderId);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setStatus(BidStatus.CANCELLED);
        bid.setRejectionReason(BidEntity.REJECTION_SENDER_NO_SHOW);
        return bid;
    }

    @Test
    void absenceConfirmee_conversationEnLectureSeule_etMessageSysteme() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(cancelledForSenderNoShow()));
        when(conversationRepository.findAllByBidId(bidId)).thenReturn(List.of(
                new ConversationEntity(bidId, senderId, travelerId, "conv-1"),
                ConversationEntity.forRecipient(bidId, UUID.randomUUID(), travelerId, "conv-recipient")));

        listener(AppLanguage.FR).onCancellationConfirmed(event(bidId, CancellationReason.SENDER_NO_SHOW));

        verify(firestoreService).markReadOnly("conv-1", "SENDER_NO_SHOW");
        verify(firestoreService).addSystemMessage("conv-1", "Conversation fermée : l'absence de l'expéditeur "
                + "à la remise a été confirmée. Les messages restent lisibles.");
        // La conversation destinataire n'est pas concernée.
        verify(firestoreService, never()).markReadOnly(eq("conv-recipient"), anyString());
        verify(firestoreService, never()).addSystemMessage(eq("conv-recipient"), anyString());
    }

    @Test
    void messageSysteme_dansLaLangueDeLExpediteur() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(cancelledForSenderNoShow()));
        when(conversationRepository.findAllByBidId(bidId)).thenReturn(List.of(
                new ConversationEntity(bidId, senderId, travelerId, "conv-1")));

        listener(AppLanguage.EN).onCancellationConfirmed(event(bidId, CancellationReason.SENDER_NO_SHOW));

        verify(firestoreService).addSystemMessage("conv-1", "Conversation closed: the sender's absence at "
                + "drop-off was confirmed. Messages remain readable.");
    }

    @Test
    void autreMotif_rienNeChange() {
        listener(AppLanguage.FR).onCancellationConfirmed(
                event(bidId, CancellationReason.SENDER_CANCEL_AFTER_HANDOVER));

        verifyNoInteractions(bidRepository, conversationRepository, firestoreService);
    }

    @Test
    void bidSansLeMotif_rienNeChange() {
        // Confirmation rejouée sur un bid déjà remis : il n'a pas été annulé pour absence.
        BidEntity handedOver = new BidEntity();
        handedOver.setStatus(BidStatus.HANDED_OVER);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(handedOver));

        listener(AppLanguage.FR).onCancellationConfirmed(event(bidId, CancellationReason.SENDER_NO_SHOW));

        verifyNoInteractions(conversationRepository, firestoreService);
    }

    @Test
    void bidIntrouvable_rienNeChange() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());

        listener(AppLanguage.FR).onCancellationConfirmed(event(bidId, CancellationReason.SENDER_NO_SHOW));

        verifyNoInteractions(conversationRepository, firestoreService);
    }

    @Test
    void bidNul_rienNeChange() {
        listener(AppLanguage.FR).onCancellationConfirmed(event(null, CancellationReason.SENDER_NO_SHOW));

        verifyNoInteractions(bidRepository, conversationRepository, firestoreService);
    }

    @Test
    void echecFirestore_journaliseSansPropager_etContinueAvecLesAutres() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(cancelledForSenderNoShow()));
        when(conversationRepository.findAllByBidId(bidId)).thenReturn(List.of(
                new ConversationEntity(bidId, senderId, travelerId, "conv-1"),
                new ConversationEntity(bidId, senderId, travelerId, "conv-2")));
        doThrow(new RuntimeException("Firestore markReadOnly failed"))
                .when(firestoreService).markReadOnly(eq("conv-1"), any());

        assertThatNoException().isThrownBy(() -> listener(AppLanguage.FR)
                .onCancellationConfirmed(event(bidId, CancellationReason.SENDER_NO_SHOW)));

        verify(firestoreService).markReadOnly("conv-2", "SENDER_NO_SHOW");
    }
}
