package com.yadony.api.matching.reception;

import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.matching.events.BidMaterializedEvent;
import com.yadony.api.matching.events.BidRecipientChangedEvent;
import com.yadony.api.payments.events.MobileMoneyPaymentConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Rattache le colis à son destinataire dès qu'il est accepté, quel que soit le chemin :
 * acceptation carte ou espèces ({@link BidAcceptedEvent}), paiement mobile money
 * encaissé ({@link MobileMoneyPaymentConfirmedEvent}), fil de négociation matérialisé
 * ({@link BidMaterializedEvent}), et à nouveau quand l'expéditeur change de destinataire
 * ({@link BidRecipientChangedEvent}) : le nouveau numéro a peut-être un compte.
 *
 * <p>Après validation et en asynchrone : l'acceptation est déjà acquise, et aucune
 * erreur ici ne doit remonter jusqu'à elle.
 */
@Component
public class ReceptionLinkListener {

    private static final Logger log = LoggerFactory.getLogger(ReceptionLinkListener.class);

    private final ReceptionLinker linker;

    public ReceptionLinkListener(ReceptionLinker linker) {
        this.linker = linker;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidAccepted(BidAcceptedEvent event) {
        // Mobile money : publié quand le bid attend encore son paiement. Le rattachement
        // suit la confirmation du paiement (MobileMoneyPaymentConfirmedEvent).
        if (event.isMobileMoney()) {
            return;
        }
        link(event.getBidId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMobileMoneyPaymentConfirmed(MobileMoneyPaymentConfirmedEvent event) {
        link(event.bidId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidMaterialized(BidMaterializedEvent event) {
        link(event.getBidId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBidRecipientChanged(BidRecipientChangedEvent event) {
        link(event.bidId());
    }

    private void link(UUID bidId) {
        try {
            linker.linkIfPossible(bidId);
        } catch (Exception e) {
            log.warn("Rattachement du destinataire impossible pour le colis {} : {}", bidId, e.toString());
        }
    }
}
