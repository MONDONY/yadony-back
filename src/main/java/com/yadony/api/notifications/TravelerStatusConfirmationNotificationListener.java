package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.travelerstatus.events.IndividualFrequencyFlaggedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Lot 4 : pose au particulier signalé la question neutre « Confirmez votre statut », une seule fois, à la création
 * du signalement. Notification in-app et push dans sa langue, qui ouvre l'écran GP Pro. Ni chiffre, ni seuil, ni
 * menace : aucune conséquence n'est attachée. AFTER_COMMIT : un contrôle annulé ne questionne personne.
 */
@Component
public class TravelerStatusConfirmationNotificationListener {

    static final String TYPE = "TRAVELER_STATUS_CONFIRMATION";
    static final String ROUTE = "/profile/gp-pro";

    private final NotificationDispatcher dispatcher;

    public TravelerStatusConfirmationNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFlagged(IndividualFrequencyFlaggedEvent event) {
        Messages m = dispatcher.messagesFor(event.userId());
        NotificationText text = NotificationTexts.travelerStatusConfirmation(m);
        dispatcher.notifyUser(event.userId(), text.title(), text.body(), Map.of("type", TYPE, "route", ROUTE));
    }
}
