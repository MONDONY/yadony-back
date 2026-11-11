package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Récapitulatif DAC7 : push et boîte de l'app avec la phrase courte, e-mail avec le détail. */
class NotificationDispatcherEmailDetailTest {

    @Test
    @SuppressWarnings("unchecked")
    void pushCourt_emailDetaille() throws Exception {
        FcmService fcm = mock(FcmService.class);
        NotificationService notifications = mock(NotificationService.class);
        NotificationEmailService emails = mock(NotificationEmailService.class);
        NotificationEntity entity = new NotificationEntity(UUID.randomUUID(), "TAX_STATEMENT", "t", "b", Map.of(),
                false);
        var field = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, UUID.randomUUID());
        when(notifications.persist(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(entity);
        NotificationDispatcher dispatcher = new NotificationDispatcher(fcm, mock(SmsService.class),
                mock(UserRepository.class), notifications, mock(com.yadony.api.common.BlockVisibility.class),
                null, TestMessages.resolver(), emails);
        UUID user = UUID.randomUUID();

        dispatcher.notifyUserWithEmailDetail(user, "Récapitulatif fiscal 2026", "Court", "Détail : 95 €",
                Map.of("type", "TAX_STATEMENT"));

        verify(notifications).persist(eq(user), eq("TAX_STATEMENT"), eq("Récapitulatif fiscal 2026"), eq("Court"),
                any(), eq(false));
        verify(emails).send(eq(user), eq("TAX_STATEMENT"), eq("Récapitulatif fiscal 2026"), eq("Détail : 95 €"),
                any());
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(fcm).sendToUser(eq(user), eq("Récapitulatif fiscal 2026"), eq("Court"), data.capture());
        assertThat(data.getValue()).containsEntry("type", "TAX_STATEMENT");
    }
}
