package com.yadony.api.alerts;

import com.yadony.api.common.BlockVisibility;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.requests.entity.PackageRequestEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorridorAlertDigestSchedulerTest {

    @Mock CorridorAlertRepository alertRepository;
    @Mock AlertService alertService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock BlockVisibility blockVisibility;

    CorridorAlertDigestScheduler scheduler;

    final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        scheduler = new CorridorAlertDigestScheduler(
                alertRepository, alertService, notificationDispatcher, blockVisibility);
    }

    private static void setId(Object target, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private CorridorAlertEntity alert(LocalDateTime lastNotifiedAt) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        setId(a, UUID.randomUUID());
        a.setOwnerId(ownerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setActive(true);
        a.setLastNotifiedAt(lastNotifiedAt);
        a.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);
        return a;
    }

    private PackageRequestEntity pkg(UUID senderId) {
        PackageRequestEntity p = new PackageRequestEntity();
        p.setSenderId(senderId);
        return p;
    }

    private AnnouncementEntity trip(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        return a;
    }

    /** Fenêtre de dates passée : le digest ne cherche même plus de correspondances. */
    @Test
    void skipsExpiredAlerts() {
        CorridorAlertEntity expired = alert(null);
        expired.setDateFrom(LocalDate.of(2020, 1, 1));
        expired.setDateTo(LocalDate.of(2020, 1, 31));
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(expired));

        scheduler.runDigest();

        verifyNoInteractions(alertService, notificationDispatcher);
        verify(alertRepository, never()).save(any());
    }

    /** Silencieuse : le digest ne la regarde pas ; quotidienne : traitée comme avant. */
    @Test
    void skipsMutedAlerts_butDigestsDailyOnes() {
        CorridorAlertEntity muted = alert(null);
        muted.setNotifyMode(AlertNotifyMode.MUTED);
        CorridorAlertEntity daily = alert(null);
        daily.setNotifyMode(AlertNotifyMode.DAILY);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(muted, daily));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of());
        when(alertService.findRecentMatches(eq(daily), any()))
                .thenReturn(List.of(pkg(UUID.randomUUID())));

        scheduler.runDigest();

        verify(alertService, never()).findRecentMatches(eq(muted), any());
        verify(notificationDispatcher).notifyUser(eq(ownerId), anyString(), anyString(), anyMap());
        assertThat(daily.getLastNotifiedAt()).isNotNull();
        assertThat(muted.getLastNotifiedAt()).isNull();
    }

    @Test
    void dispatchesAndBumpsLastNotified_whenMatchesExist() {
        CorridorAlertEntity a = alert(null);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of());
        when(alertService.findRecentMatches(eq(a), any()))
                .thenReturn(List.of(pkg(UUID.randomUUID()), pkg(UUID.randomUUID())));

        scheduler.runDigest();

        ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), anyString(), anyString(), dataCaptor.capture());
        assertThat(dataCaptor.getValue().get("type")).isEqualTo("CORRIDOR_ALERT");
        assertThat(a.getLastNotifiedAt()).isNotNull();
        verify(alertRepository).save(a);
    }

    @Test
    void skipsAlert_whenNoMatches() {
        CorridorAlertEntity a = alert(LocalDateTime.now().minusDays(1));
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of());
        when(alertService.findRecentMatches(eq(a), any())).thenReturn(List.of());

        scheduler.runDigest();

        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void usesLastNotifiedAt_asSinceCutoff() {
        LocalDateTime last = LocalDateTime.of(2026, 6, 1, 9, 0);
        CorridorAlertEntity a = alert(last);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of());
        when(alertService.findRecentMatches(eq(a), eq(last))).thenReturn(List.of(pkg(UUID.randomUUID())));

        scheduler.runDigest();

        verify(alertService).findRecentMatches(a, last);
    }

    @Test
    void tripDirection_usesTripWordingAndTripRecentMatches() {
        CorridorAlertEntity a = alert(null);
        a.setDirection(AlertDirection.SENDER_WANTS_TRIPS);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of());
        when(alertService.findRecentTripMatches(eq(a), any()))
                .thenReturn(List.of(trip(UUID.randomUUID()), trip(UUID.randomUUID())));

        scheduler.runDigest();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), anyString(), bodyCaptor.capture(), dataCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("trajets");
        assertThat(dataCaptor.getValue().get("direction")).isEqualTo("SENDER_WANTS_TRIPS");
        assertThat(dataCaptor.getValue().get("type")).isEqualTo("CORRIDOR_ALERT");
    }

    @Test
    void packageDirection_usesPackageWording() {
        CorridorAlertEntity a = alert(null);
        a.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of());
        when(alertService.findRecentMatches(eq(a), any()))
                .thenReturn(List.of(pkg(UUID.randomUUID())));

        scheduler.runDigest();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), anyString(), bodyCaptor.capture(), dataCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("colis");
        assertThat(dataCaptor.getValue().get("direction")).isEqualTo("TRAVELER_WANTS_PACKAGES");
    }

    // ── Confidentialité — masquage des contenus d'utilisateurs bloqués ────────────

    /** Tous les colis viennent d'expéditeurs masqués : plus rien à annoncer, aucun digest. */
    @Test
    void packageDirection_allMatchesHidden_doesNotNotify() {
        UUID hiddenSender = UUID.randomUUID();
        CorridorAlertEntity a = alert(null);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of(hiddenSender));
        when(alertService.findRecentMatches(eq(a), any()))
                .thenReturn(List.of(pkg(hiddenSender), pkg(hiddenSender)));

        scheduler.runDigest();

        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
        verify(alertRepository, never()).save(any());
    }

    /** Le décompte annoncé ne porte que sur les contenus visibles. */
    @Test
    void packageDirection_countsOnlyVisibleMatches() {
        UUID hiddenSender = UUID.randomUUID();
        CorridorAlertEntity a = alert(null);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of(hiddenSender));
        when(alertService.findRecentMatches(eq(a), any()))
                .thenReturn(List.of(pkg(hiddenSender), pkg(UUID.randomUUID())));

        scheduler.runDigest();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), anyString(), bodyCaptor.capture(), anyMap());
        assertThat(bodyCaptor.getValue()).contains(": 1 colis correspond.");
    }

    /** Même règle côté trajets : un voyageur masqué ne pèse pas dans le digest. */
    @Test
    void tripDirection_hiddenTravelerExcludedFromCount() {
        UUID hiddenTraveler = UUID.randomUUID();
        CorridorAlertEntity a = alert(null);
        a.setDirection(AlertDirection.SENDER_WANTS_TRIPS);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(a));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of(hiddenTraveler));
        when(alertService.findRecentTripMatches(eq(a), any()))
                .thenReturn(List.of(trip(hiddenTraveler), trip(UUID.randomUUID())));

        scheduler.runDigest();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationDispatcher).notifyUser(eq(ownerId), anyString(), bodyCaptor.capture(), anyMap());
        assertThat(bodyCaptor.getValue()).contains(": 1 trajet correspond.");
    }

    /**
     * Performance — le jeu masqué est résolu une seule fois par destinataire, même quand
     * celui-ci possède plusieurs alertes et que chacune remonte plusieurs éléments.
     */
    @Test
    void resolvesHiddenSetOncePerRecipient() {
        CorridorAlertEntity first = alert(null);
        CorridorAlertEntity second = alert(null);
        when(alertRepository.findAllByActiveTrue()).thenReturn(List.of(first, second));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(Set.of());
        when(alertService.findRecentMatches(any(), any()))
                .thenReturn(List.of(pkg(UUID.randomUUID()), pkg(UUID.randomUUID())));

        scheduler.runDigest();

        verify(blockVisibility, times(1)).hiddenUserIdsFor(ownerId);
    }
}
