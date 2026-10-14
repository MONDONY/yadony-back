package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.referral.events.ReferralRewardAvailableEvent;
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
class ReferralRewardAvailableNotificationListenerTest {

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private ReferralRewardAvailableNotificationListener listener;

    private final UUID referrerId = UUID.randomUUID();
    private final UUID invitationId = UUID.randomUUID();

    @Test
    void francais_nommeLeFilleul_etOuvreLEcranParrainage() {
        when(notificationDispatcher.messagesFor(referrerId)).thenReturn(TestMessages.fr());

        listener.onRewardAvailable(new ReferralRewardAvailableEvent(invitationId, referrerId, "Awa D."));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(referrerId), eq("Récompense de parrainage"),
                eq("Awa D. a envoyé son 1er colis : choisissez votre récompense."), data.capture());
        assertThat(data.getValue())
                .containsEntry("type", "REFERRAL_REWARD_AVAILABLE")
                .containsEntry("invitationId", invitationId.toString())
                .containsEntry("route", "/profile/referral");
    }

    @Test
    void anglais_sansPrenom() {
        when(notificationDispatcher.messagesFor(referrerId)).thenReturn(TestMessages.en());

        listener.onRewardAvailable(new ReferralRewardAvailableEvent(invitationId, referrerId, null));

        verify(notificationDispatcher).notifyUser(eq(referrerId), eq("Referral reward ready"),
                eq("Your referee sent their first parcel: choose your reward."), any());
    }
}
