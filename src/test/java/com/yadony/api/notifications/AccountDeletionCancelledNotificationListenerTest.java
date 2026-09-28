package com.yadony.api.notifications;

import com.yadony.api.auth.events.AccountDeletionCancelledByAdminEvent;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountDeletionCancelledNotificationListenerTest {

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private AccountDeletionCancelledNotificationListener listener;

    private final UUID userId = UUID.randomUUID();

    @Test
    void francais_vouvoiement_sansTiretCadratin() {
        when(notificationDispatcher.messagesFor(userId)).thenReturn(TestMessages.fr());

        listener.onCancelled(new AccountDeletionCancelledByAdminEvent(userId, UUID.randomUUID()));

        verify(notificationDispatcher).notifyUser(eq(userId), eq("Suppression de compte annulée"),
                eq("Votre demande de suppression de compte a été annulée par l'équipe Yadony."),
                eq(Map.of("type", "ACCOUNT_DELETION_CANCELLED")));
    }

    @Test
    void anglais() {
        when(notificationDispatcher.messagesFor(userId)).thenReturn(TestMessages.en());

        listener.onCancelled(new AccountDeletionCancelledByAdminEvent(userId, UUID.randomUUID()));

        verify(notificationDispatcher).notifyUser(eq(userId), eq("Account deletion cancelled"),
                eq("Your account deletion request was cancelled by the Yadony team."),
                eq(Map.of("type", "ACCOUNT_DELETION_CANCELLED")));
    }

    @Test
    void rangeeDansLesAnnoncesDuFil() {
        assertThat(NotificationCategory.fromType("ACCOUNT_DELETION_CANCELLED")).isEqualTo(NotificationCategory.ANNONCE);
    }
}
