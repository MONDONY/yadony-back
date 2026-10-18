package com.yadony.api.subscriptions;

import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementPublishedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravelerAvailabilityListenerTest {

    @Mock TravelerSubscriptionRepository repo;
    @Mock NotificationDispatcher dispatcher;
    @Mock BlockVisibility blockVisibility;
    @InjectMocks TravelerAvailabilityListener listener;

    /** Dispatcher mocké : sans stub, {@code messagesFor} rend {@code null} → NPE. Français par défaut. */
    @BeforeEach
    void stubMessages() {
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    AnnouncementPublishedEvent event(UUID travelerId) {
        return new AnnouncementPublishedEvent(UUID.randomUUID(), travelerId, "Ibrahima D", "Paris", "Dakar");
    }

    private TravelerSubscriptionEntity sub(UUID travelerId, boolean push) {
        TravelerSubscriptionEntity s = new TravelerSubscriptionEntity();
        s.setSenderId(UUID.randomUUID());
        s.setTravelerId(travelerId);
        s.setPushEnabled(push);
        return s;
    }

    @Test
    void notifies_inAppAlways_pushOnlyIfEnabled() {
        UUID travelerId = UUID.randomUUID();
        TravelerSubscriptionEntity pushOff = sub(travelerId, false);
        TravelerSubscriptionEntity pushOn = sub(travelerId, true);
        when(repo.findAllByTravelerId(travelerId)).thenReturn(List.of(pushOff, pushOn));
        when(repo.markNewIfUnseen(any())).thenReturn(1);

        listener.onAnnouncementPublished(event(travelerId));

        verify(repo, times(2)).markNewIfUnseen(any());
        verify(dispatcher).notifyUnlessBlocked(
                eq(pushOff.getSenderId()), eq(travelerId), anyString(), anyString(), anyMap(), eq(false));
        verify(dispatcher).notifyUnlessBlocked(
                eq(pushOn.getSenderId()), eq(travelerId), anyString(), anyString(), anyMap(), eq(true));
    }

    /**
     * Bruit : un voyageur suivi publie en rafale (récurrents générés la nuit, étapes d'un
     * voyage). Tant que l'abonné n'a pas regardé la nouveauté précédente, la suivante
     * reste dans le feed sans faire sonner le téléphone.
     */
    @Test
    void alreadyUnseenNews_staysInFeedWithoutPush() {
        UUID travelerId = UUID.randomUUID();
        TravelerSubscriptionEntity pushOn = sub(travelerId, true);
        when(repo.findAllByTravelerId(travelerId)).thenReturn(List.of(pushOn));
        when(repo.markNewIfUnseen(any())).thenReturn(0);

        listener.onAnnouncementPublished(event(travelerId));

        verify(dispatcher).notifyUnlessBlocked(
                eq(pushOn.getSenderId()), eq(travelerId), anyString(), anyString(), anyMap(), eq(false));
        verify(dispatcher, never()).notifyUnlessBlocked(
                any(), any(), anyString(), anyString(), anyMap(), eq(true));
    }

    @Test
    void noSubscribers_doesNothing() {
        UUID travelerId = UUID.randomUUID();
        when(repo.findAllByTravelerId(travelerId)).thenReturn(List.of());
        listener.onAnnouncementPublished(event(travelerId));
        verifyNoInteractions(dispatcher);
    }

    /**
     * Confidentialité — abonné masqué : ni notification, ni pastille « nouveau ». Poser
     * hasNew afficherait un badge pointant vers un trajet que l'abonné ne peut pas ouvrir,
     * et rendrait donc le blocage visible.
     */
    @Test
    void blockedSubscriber_getsNoNotificationAndNoHasNewBadge() {
        UUID travelerId = UUID.randomUUID();
        TravelerSubscriptionEntity blocked = sub(travelerId, true);
        TravelerSubscriptionEntity visible = sub(travelerId, true);
        when(repo.findAllByTravelerId(travelerId)).thenReturn(List.of(blocked, visible));
        when(blockVisibility.isHidden(blocked.getSenderId(), travelerId)).thenReturn(true);
        when(repo.markNewIfUnseen(any())).thenReturn(1);

        listener.onAnnouncementPublished(event(travelerId));

        verify(repo, times(1)).markNewIfUnseen(any());
        verify(dispatcher, never()).notifyUnlessBlocked(
                eq(blocked.getSenderId()), any(), anyString(), anyString(), anyMap(), anyBoolean());
        verify(dispatcher).notifyUnlessBlocked(
                eq(visible.getSenderId()), eq(travelerId), anyString(), anyString(), anyMap(), eq(true));
        // La voie générique, aveugle au blocage, ne doit jamais être empruntée ici.
        verify(dispatcher, never()).notifyUser(any(), anyString(), anyString(), anyMap(), anyBoolean());
    }
}
