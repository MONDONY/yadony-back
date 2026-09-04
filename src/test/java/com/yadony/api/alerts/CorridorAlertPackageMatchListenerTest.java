package com.yadony.api.alerts;

import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.event.PackageRequestCreatedEvent;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class CorridorAlertPackageMatchListenerTest {

    @Mock PackageRequestRepository packageRequestRepository;
    @Mock AlertService alertService;
    @Mock CorridorAlertRepository alertRepository;
    @Mock NotificationDispatcher notificationDispatcher;
    @InjectMocks CorridorAlertPackageMatchListener listener;

    final UUID requestId = UUID.randomUUID();
    final UUID senderId = UUID.randomUUID();

    private static void setId(Object target, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private PackageRequestEntity pkg() {
        PackageRequestEntity p = new PackageRequestEntity();
        setId(p, requestId);
        p.setSenderId(senderId);
        p.setDepartureCity("Paris");
        p.setArrivalCity("Bamako");
        return p;
    }

    private CorridorAlertEntity alert(LocalDateTime lastNotifiedAt) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        setId(a, UUID.randomUUID());
        a.setOwnerId(UUID.randomUUID());
        a.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setActive(true);
        a.setLastNotifiedAt(lastNotifiedAt);
        return a;
    }

    private PackageRequestCreatedEvent event() {
        return new PackageRequestCreatedEvent(requestId, senderId, "Paris", "Bamako", LocalDate.of(2026, 9, 20));
    }

    @Test
    void onCreated_match_notifiesWithRequestIdAndStampsLastNotified() {
        PackageRequestEntity p = pkg();
        CorridorAlertEntity alert = alert(null);
        when(packageRequestRepository.findById(requestId)).thenReturn(Optional.of(p));
        when(alertService.findTravelerAlertsMatchingPackage(p)).thenReturn(List.of(alert));
        when(notificationDispatcher.notifyUnlessBlocked(
                eq(alert.getOwnerId()), eq(senderId), any(), any(), anyMap())).thenReturn(true);

        listener.onPackageRequestCreated(event());

        verify(notificationDispatcher).notifyUnlessBlocked(
                eq(alert.getOwnerId()), eq(senderId), contains("Nouveau colis"), any(),
                argThat(d -> requestId.toString().equals(d.get("requestId"))
                        && "CORRIDOR_ALERT".equals(d.get("type"))
                        && alert.getId().toString().equals(d.get("alertId"))));
        assertThat(alert.getLastNotifiedAt()).isNotNull();
        verify(alertRepository).save(alert);
    }

    /** Masqués l'un pour l'autre : pas d'horodatage, le digest garde sa fenêtre. */
    @Test
    void onCreated_ownerBlockedWithSender_noStampNoSave() {
        PackageRequestEntity p = pkg();
        CorridorAlertEntity alert = alert(null);
        when(packageRequestRepository.findById(requestId)).thenReturn(Optional.of(p));
        when(alertService.findTravelerAlertsMatchingPackage(p)).thenReturn(List.of(alert));
        when(notificationDispatcher.notifyUnlessBlocked(
                eq(alert.getOwnerId()), eq(senderId), any(), any(), anyMap())).thenReturn(false);

        listener.onPackageRequestCreated(event());

        assertThat(alert.getLastNotifiedAt()).isNull();
        verify(alertRepository, never()).save(any());
    }

    @Test
    void onCreated_withinCooldown_doesNotNotify() {
        PackageRequestEntity p = pkg();
        CorridorAlertEntity alert = alert(LocalDateTime.now(ZoneOffset.UTC));
        when(packageRequestRepository.findById(requestId)).thenReturn(Optional.of(p));
        when(alertService.findTravelerAlertsMatchingPackage(p)).thenReturn(List.of(alert));

        listener.onPackageRequestCreated(event());

        verifyNoInteractions(notificationDispatcher);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void onCreated_noMatch_doesNotNotify() {
        PackageRequestEntity p = pkg();
        when(packageRequestRepository.findById(requestId)).thenReturn(Optional.of(p));
        when(alertService.findTravelerAlertsMatchingPackage(p)).thenReturn(List.of());

        listener.onPackageRequestCreated(event());

        verifyNoInteractions(notificationDispatcher);
    }

    @Test
    void onCreated_requestNotFound_noop() {
        when(packageRequestRepository.findById(requestId)).thenReturn(Optional.empty());

        listener.onPackageRequestCreated(event());

        verifyNoInteractions(alertService, notificationDispatcher, alertRepository);
    }
}
