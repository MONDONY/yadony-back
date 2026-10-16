package com.yadony.api.messaging;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Absence de l'expéditeur à la remise CONFIRMÉE (confirmation, délai de contestation écoulé ou
 * arbitrage admin, FLUTTER-QQ) : la conversation expéditeur ↔ voyageur du colis passe en
 * lecture seule pour les deux. Pendant les 24 h de contestation (simple signalement), rien
 * ne change.
 *
 * <p>La lecture seule côté API est lue sur le bid ({@code ConversationService}, motif
 * {@link BidEntity#REJECTION_SENDER_NO_SHOW} posé par {@code SenderNoShowBidCancelListener}
 * dans la transaction de confirmation). Ici, après commit : miroir Firestore
 * ({@link FirestoreService#markReadOnly}, champ {@code readOnlyAt}) pour que les règles
 * refusent tout nouveau message, puis un message système, dans la langue de l'expéditeur
 * comme les autres messages système du colis. Conversation destinataire non concernée.
 *
 * <p>Ne réagit que si le bid porte bien le motif : une confirmation rejouée sur un bid déjà
 * sorti de ACCEPTED autrement (remis, annulé) ne ferme rien.
 */
@Component
public class SenderNoShowConversationListener {

    private static final Logger log = LoggerFactory.getLogger(SenderNoShowConversationListener.class);

    static final String MESSAGE_KEY = "conversation.closed-sender-no-show";

    private final ConversationRepository conversationRepository;
    private final BidRepository bidRepository;
    private final FirestoreService firestoreService;
    private final MessagesResolver messagesResolver;

    public SenderNoShowConversationListener(ConversationRepository conversationRepository,
                                            BidRepository bidRepository,
                                            FirestoreService firestoreService,
                                            MessagesResolver messagesResolver) {
        this.conversationRepository = conversationRepository;
        this.bidRepository = bidRepository;
        this.firestoreService = firestoreService;
        this.messagesResolver = messagesResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        if (event.reason() != CancellationReason.SENDER_NO_SHOW || event.bidId() == null) {
            return;
        }
        BidEntity bid = bidRepository.findById(event.bidId()).orElse(null);
        if (!com.yadony.api.matching.ContactWindow.isClosedBySenderNoShow(bid)) {
            return;
        }
        for (ConversationEntity conv : conversationRepository.findAllByBidId(event.bidId())) {
            if (conv.isRecipientConversation()) {
                continue;
            }
            try {
                firestoreService.markReadOnly(conv.getFirestoreConversationId(),
                        CancellationReason.SENDER_NO_SHOW.name());
                firestoreService.addSystemMessage(conv.getFirestoreConversationId(),
                        messagesResolver.forUser(bid.getSenderId()).get(MESSAGE_KEY));
            } catch (Exception e) {
                log.error("Fermeture de la conversation {} (absence expéditeur, bid {}) impossible : {}",
                        conv.getId(), event.bidId(), e.getMessage(), e);
            }
        }
    }
}
