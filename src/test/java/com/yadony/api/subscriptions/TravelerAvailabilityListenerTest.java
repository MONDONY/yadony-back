package com.yadony.api.subscriptions;

import com.yadony.api.matching.AnnouncementPublishedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
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
    @InjectMocks TravelerAvailabilityListener listener;

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
        when(dispatcher.notifyUnlessBlocked(any(), eq(travelerId), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(true);

        listener.onAnnouncementPublished(event(travelerId));

        verify(repo, times(2)).save(any(TravelerSubscriptionEntity.class));
        verify(dispatcher).notifyUnlessBlocked(
                eq(pushOff.getSenderId()), eq(travelerId), anyString(), anyString(), anyMap(), eq(false));
        verify(dispatcher).notifyUnlessBlocked(
                eq(pushOn.getSenderId()), eq(travelerId), anyString(), anyString(), anyMap(), eq(true));
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
        when(dispatcher.notifyUnlessBlocked(
                eq(blocked.getSenderId()), eq(travelerId), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(false);
        when(dispatcher.notifyUnlessBlocked(
                eq(visible.getSenderId()), eq(travelerId), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(true);

        listener.onAnnouncementPublished(event(travelerId));

        assertThat(blocked.isHasNew()).isFalse();
        assertThat(visible.isHasNew()).isTrue();
        verify(repo, times(1)).save(visible);
        verify(repo, never()).save(blocked);
        // La voie générique, aveugle au blocage, ne doit jamais être empruntée ici.
        verify(dispatcher, never()).notifyUser(any(), anyString(), anyString(), anyMap(), anyBoolean());
    }
}
