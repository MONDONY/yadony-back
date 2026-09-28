package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.signalements.events.ReportResolvedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Remercie le signalant quand son signalement est traité : in-app et push, dans sa langue,
 * sans aucun détail sur la décision ni la sanction (le signalé ne doit rien pouvoir en
 * déduire, le signalant n'a pas à savoir qui a été sanctionné). Pas de lien profond.
 * AFTER_COMMIT : une résolution annulée n'annonce rien.
 */
@Component
public class ReportResolvedNotificationListener {

    static final String TYPE = "REPORT_RESOLVED";

    private final NotificationDispatcher notificationDispatcher;

    public ReportResolvedNotificationListener(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onResolved(ReportResolvedEvent event) {
        if (event.reporterId() == null) {
            return;
        }
        Messages m = notificationDispatcher.messagesFor(event.reporterId());
        NotificationText text = NotificationTexts.reportResolved(m);
        notificationDispatcher.notifyUser(event.reporterId(), text.title(), text.body(), Map.of("type", TYPE));
    }
}
