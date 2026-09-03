package com.yadony.api.matching;

import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationPrefsService;
import com.yadony.api.requests.event.PackageRequestCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageMatchTravelerNotifyListenerTest {

    @Mock MatchingService matchingService;
    @Mock NotificationPrefsService notificationPrefsService;
    @Mock NotificationDispatcher notificationDispatcher;
    @InjectMocks PackageMatchTravelerNotifyListener listener;

    final UUID requestId = UUID.randomUUID();
    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();

    private PackageRequestCreatedEvent event() {
        return new PackageRequestCreatedEvent(
                requestId, senderId, "Paris", "Bamako", LocalDate.now().plusDays(5));
    }

    @Test
    void onCreated_matchAndEnabled_notifiesWithDeepLinkPayload() {
        when(matchingService.findTravelersMatchingPackage(requestId)).thenReturn(List.of(travelerId));
        when(notificationPrefsService.isPackageMatchEnabled(travelerId)).thenReturn(true);

        listener.onPackageRequestCreated(event());

        verify(notificationDispatcher).notifyUnlessBlocked(
                eq(travelerId), eq(senderId), contains("colis pour votre trajet"), any(),
                argThat(d -> "PACKAGE_MATCH".equals(d.get("type"))
                        && requestId.toString().equals(d.get("requestId"))));
    }

    @Test
    void onCreated_matchButDisabled_doesNotNotify() {
        when(matchingService.findTravelersMatchingPackage(requestId)).thenReturn(List.of(travelerId));
        when(notificationPrefsService.isPackageMatchEnabled(travelerId)).thenReturn(false);

        listener.onPackageRequestCreated(event());

        verify(notificationDispatcher, never()).notifyUnlessBlocked(any(), any(), any(), any(), anyMap());
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), anyMap());
    }

    @Test
    void onCreated_noMatch_doesNotNotify() {
        when(matchingService.findTravelersMatchingPackage(requestId)).thenReturn(List.of());

        listener.onPackageRequestCreated(event());

        verifyNoInteractions(notificationPrefsService, notificationDispatcher);
    }

    /**
     * Confidentialité — le voyageur et l'expéditeur sont masqués l'un pour l'autre : le
     * listener passe l'émetteur au dispatcher, qui supprime l'envoi. La voie générique
     * {@code notifyUser}, aveugle au blocage, ne doit jamais être empruntée ici.
     */
    @Test
    void onCreated_travelerBlockedWithSender_dispatcherSuppresses() {
        when(matchingService.findTravelersMatchingPackage(requestId)).thenReturn(List.of(travelerId));
        when(notificationPrefsService.isPackageMatchEnabled(travelerId)).thenReturn(true);
        when(notificationDispatcher.notifyUnlessBlocked(
                eq(travelerId), eq(senderId), any(), any(), anyMap())).thenReturn(false);

        listener.onPackageRequestCreated(event());

        verify(notificationDispatcher).notifyUnlessBlocked(
                eq(travelerId), eq(senderId), any(), any(), anyMap());
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), anyMap());
    }
}
