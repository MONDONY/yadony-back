package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.travelerstatus.events.IndividualFrequencyFlaggedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TravelerStatusConfirmationNotificationListenerTest {

    @Mock private NotificationDispatcher dispatcher;

    @InjectMocks private TravelerStatusConfirmationNotificationListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID flagId = UUID.randomUUID();

    @Test
    void francais_questionNeutre_quiOuvreLEcranGpPro() {
        when(dispatcher.messagesFor(userId)).thenReturn(TestMessages.fr());

        listener.onFlagged(new IndividualFrequencyFlaggedEvent(userId, flagId));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).notifyUser(eq(userId), eq("Confirmez votre statut"), body.capture(), data.capture());
        assertThat(data.getValue())
                .containsEntry("type", "TRAVELER_STATUS_CONFIRMATION")
                .containsEntry("route", "/profile/gp-pro")
                .doesNotContainKey("flagId");
        // Neutre : aucune accusation, aucun chiffre, aucune menace de blocage.
        assertThat(body.getValue()).contains("particulier", "GP Pro")
                .doesNotContainIgnoringCase("bloqu").doesNotContainIgnoringCase("sanction")
                .doesNotContain("—");
    }

    @Test
    void anglais() {
        when(dispatcher.messagesFor(userId)).thenReturn(TestMessages.en());

        listener.onFlagged(new IndividualFrequencyFlaggedEvent(userId, flagId));

        verify(dispatcher).notifyUser(eq(userId), eq("Confirm your status"), any(), any());
    }

    @Test
    void routageEtFamille() {
        assertThat(NotificationDeeplink.of("TRAVELER_STATUS_CONFIRMATION", Map.of()))
                .contains("yadony://profile/gp-pro");
        assertThat(NotificationCategory.fromType("TRAVELER_STATUS_CONFIRMATION")).isEqualTo(NotificationCategory.ANNONCE);
    }

    /** Le push respecte les réglages (« Rappels et conseils ») ; la ligne du centre de notifications reste. */
    @Test
    void respecteLesPreferences_pasToujoursActive() {
        assertThat(NotificationPrefsService.TYPE_TO_PREF)
                .containsEntry("TRAVELER_STATUS_CONFIRMATION", "pushRemindersTips");
        assertThat(NotificationPrefsService.isAlwaysOn("TRAVELER_STATUS_CONFIRMATION")).isFalse();
    }
}
