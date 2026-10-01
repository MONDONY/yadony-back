package com.yadony.api.messaging;

import com.yadony.api.matching.events.BidRecipientChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * L'expéditeur a changé de destinataire (lot 3A) : la conversation voyageur ↔ ancien
 * destinataire est fermée. L'ancien destinataire la perd (liste, accès par id, Firestore),
 * le voyageur la garde en lecture seule. Le nouveau destinataire, une fois confirmé, en
 * ouvrira une nouvelle.
 *
 * <p>Un lien CONFIRMED ne peut pas passer DECLINED (ReceptionService#decline le refuse) et
 * n'est soft-deleted que par ce changement : cet évènement couvre donc les deux cas. Le
 * service vérifie en plus, à chaque ouverture, que le lien CONFIRMED courant correspond
 * bien au participant de la conversation.
 */
@Component
public class RecipientConversationRevocationListener {

    private static final Logger log = LoggerFactory.getLogger(RecipientConversationRevocationListener.class);

    private final RecipientConversationService recipientConversationService;

    public RecipientConversationRevocationListener(RecipientConversationService recipientConversationService) {
        this.recipientConversationService = recipientConversationService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidRecipientChanged(BidRecipientChangedEvent event) {
        try {
            int closed = recipientConversationService.revokeStale(event.bidId());
            if (closed > 0) {
                log.info("{} conversation(s) destinataire fermée(s) pour le colis {}", closed, event.bidId());
            }
        } catch (Exception e) {
            log.error("Fermeture de la conversation destinataire impossible pour le colis {} : {}",
                    event.bidId(), e.getMessage());
        }
    }
}
