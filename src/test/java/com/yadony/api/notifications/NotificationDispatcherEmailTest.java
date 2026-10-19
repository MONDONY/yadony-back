package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Étape 5 du plan bruit : l'e-mail double ou remplace le push, sans jamais bloquer le feed. */
class NotificationDispatcherEmailTest {

    private final FcmService fcm = mock(FcmService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final NotificationEmailService emails = mock(NotificationEmailService.class);
    private final UUID userId = UUID.randomUUID();
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDispatcher(fcm, mock(SmsService.class), mock(UserRepository.class),
                notifications, mock(BlockVisibility.class), null, TestMessages.resolver(), emails);
        NotificationEntity saved = new NotificationEntity(userId, "X", "t", "b", Map.of(), false);
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
        when(notifications.persist(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(saved);
    }

    @Test
    void emailInsteadOfPush_pushStaysQuiet_butFeedAndEmailHappen() {
        Map<String, String> data = Map.of("type", "FIRST_ACTION_REMINDER");
        when(emails.replacesPush(userId, "FIRST_ACTION_REMINDER", data)).thenReturn(true);

        dispatcher.notifyUser(userId, "Premiers pas", "Publiez votre premier trajet", data);

        verify(notifications).persist(eq(userId), eq("FIRST_ACTION_REMINDER"), any(), any(), any(), eq(false));
        verify(emails).send(userId, "FIRST_ACTION_REMINDER", "Premiers pas", "Publiez votre premier trajet", data);
        verify(fcm, never()).sendToUser(any(), any(), any(), any());
    }

    @Test
    void emailCannotLeave_pushIsKept() {
        Map<String, String> data = Map.of("type", "FIRST_ACTION_REMINDER");
        when(emails.replacesPush(userId, "FIRST_ACTION_REMINDER", data)).thenReturn(false);

        dispatcher.notifyUser(userId, "Premiers pas", "b", data);

        verify(fcm).sendToUser(eq(userId), eq("Premiers pas"), eq("b"), any());
    }

    @Test
    void emailWithPush_bothLeave_andAFailingEmailNeverBlocksThePush() {
        Map<String, String> data = Map.of("type", "BID_ACCEPTED");
        doThrow(new IllegalStateException("executor down")).when(emails).send(any(), any(), any(), any(), any());

        dispatcher.notifyUser(userId, "Réservation acceptée", "b", data);

        verify(emails).send(userId, "BID_ACCEPTED", "Réservation acceptée", "b", data);
        verify(fcm).sendToUser(eq(userId), eq("Réservation acceptée"), eq("b"), any());
    }

    /** Une notification feed seul (push=false) garde son e-mail, et ne consulte pas le remplacement. */
    @Test
    void feedOnlyNotification_stillGetsItsEmail() {
        Map<String, String> data = Map.of("type", "MOBILE_MONEY_PAYMENT_CONFIRMED");

        dispatcher.notifyUser(userId, "Paiement reçu", "b", data, false);

        verify(emails).send(userId, "MOBILE_MONEY_PAYMENT_CONFIRMED", "Paiement reçu", "b", data);
        verify(emails, never()).replacesPush(any(), any(), any());
        verify(fcm, never()).sendToUser(any(), any(), any(), any());
    }
}
