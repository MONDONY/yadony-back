package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.referral.events.ReferralWelcomeCodeCreatedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Souhaite la bienvenue au filleul parrainé et lui donne son code promo (-5 % sur son premier
 * envoi) : notification in-app et push, dans sa langue, qui ouvre la création d'un envoi.
 * AFTER_COMMIT : un code annulé n'annonce rien.
 */
@Component
public class ReferralWelcomeNotificationListener {

    static final String TYPE = "REFERRAL_WELCOME";
    static final String ROUTE = "/parcels/send-intro";

    private final NotificationDispatcher notificationDispatcher;

    public ReferralWelcomeNotificationListener(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onWelcomeCodeCreated(ReferralWelcomeCodeCreatedEvent event) {
        Messages m = notificationDispatcher.messagesFor(event.refereeUserId());
        NotificationText text = NotificationTexts.referralWelcome(m, event.referrerFirstName(), event.promoCode());
        notificationDispatcher.notifyUser(event.refereeUserId(), text.title(), text.body(),
                Map.of("type", TYPE, "route", ROUTE));
    }
}
