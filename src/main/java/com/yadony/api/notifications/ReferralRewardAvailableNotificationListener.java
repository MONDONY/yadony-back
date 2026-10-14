package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.referral.events.ReferralRewardAvailableEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Prévient le parrain que le premier envoi de son filleul est livré et qu'il peut
 * choisir sa récompense : notification in-app et push, dans sa langue, qui ouvre
 * l'écran Parrainage. AFTER_COMMIT : une récompense annulée n'annonce rien.
 */
@Component
public class ReferralRewardAvailableNotificationListener {

    static final String TYPE = "REFERRAL_REWARD_AVAILABLE";
    static final String ROUTE = "/profile/referral";

    private final NotificationDispatcher notificationDispatcher;

    public ReferralRewardAvailableNotificationListener(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRewardAvailable(ReferralRewardAvailableEvent event) {
        Messages m = notificationDispatcher.messagesFor(event.referrerUserId());
        NotificationText text = NotificationTexts.referralRewardAvailable(m, event.refereeDisplayName());
        notificationDispatcher.notifyUser(event.referrerUserId(), text.title(), text.body(),
                Map.of("type", TYPE,
                        "invitationId", event.invitationId().toString(),
                        "route", ROUTE));
    }
}
