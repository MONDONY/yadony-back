package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.referral.events.ReferralWelcomeCodeCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralWelcomeNotificationListenerTest {

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private ReferralWelcomeNotificationListener listener;

    private final UUID refereeId = UUID.randomUUID();
    private final UUID referrerId = UUID.randomUUID();

    @Test
    void francais_previentLeFilleul_etOuvreLaCreationDEnvoi() {
        when(notificationDispatcher.messagesFor(refereeId)).thenReturn(TestMessages.fr());

        listener.onWelcomeCodeCreated(new ReferralWelcomeCodeCreatedEvent(refereeId, referrerId, "Amina", "AMINA05"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(refereeId), eq("Bienvenue sur Yadony"),
                eq("Vous avez été parrainé par Amina. Voici votre code AMINA05 : -5 % de réduction sur votre premier envoi."),
                data.capture());
        assertThat(data.getValue())
                .containsEntry("type", "REFERRAL_WELCOME")
                .containsEntry("route", "/parcels/send-intro");
    }

    @Test
    void anglais_sansPrenomDuParrain() {
        when(notificationDispatcher.messagesFor(refereeId)).thenReturn(TestMessages.en());

        listener.onWelcomeCodeCreated(new ReferralWelcomeCodeCreatedEvent(refereeId, referrerId, null, "AMINA05"));

        verify(notificationDispatcher).notifyUser(eq(refereeId), eq("Welcome to Yadony"),
                eq("You were referred. Here is your code AMINA05: 5% off your first shipment."), any());
    }

    @Test
    void ecouteApresCommit_enAsynchrone_dansUneNouvelleTransaction() throws Exception {
        Method m = ReferralWelcomeNotificationListener.class
                .getMethod("onWelcomeCodeCreated", ReferralWelcomeCodeCreatedEvent.class);
        assertThat(m.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(m.isAnnotationPresent(Async.class)).isTrue();
        assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
