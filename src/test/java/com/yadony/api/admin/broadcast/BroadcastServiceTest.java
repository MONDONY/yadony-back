package com.yadony.api.admin.broadcast;

import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BroadcastServiceTest {

    @Mock BroadcastAudienceService audienceService;
    @Mock AdminBroadcastRepository broadcastRepository;
    @Mock NotificationDispatcher notificationDispatcher;
    @InjectMocks BroadcastService service;

    private static final UUID BROADCAST_ID = UUID.randomUUID();
    private static final BroadcastTarget ALL =
            new BroadcastTarget(BroadcastTargetType.ALL, null, null, null);

    @Test
    void recordCountsRecipientsAndPersistsHistory() {
        UUID adminId = UUID.randomUUID();
        when(audienceService.count(ALL)).thenReturn(37L);
        when(broadcastRepository.save(any(AdminBroadcastEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AdminBroadcastEntity saved = service.record("Titre", "Corps", ALL, adminId);

        assertThat(saved.getRecipientCount()).isEqualTo(37);
        assertThat(saved.getTargetType()).isEqualTo(BroadcastTargetType.ALL);
        assertThat(saved.getAdminId()).isEqualTo(adminId);
    }

    @Test
    void dispatchWalksEveryPageAndNotifiesEachRecipientOnce() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        when(audienceService.page(ALL, 0)).thenReturn(
                new PageImpl<>(List.of(u1, u2), PageRequest.of(0, 2), 3));
        when(audienceService.page(ALL, 1)).thenReturn(
                new PageImpl<>(List.of(u3), PageRequest.of(1, 2), 3));

        service.dispatchAsync(BROADCAST_ID, "Titre", "Corps", ALL);

        verify(notificationDispatcher).notifyUser(eq(u1), eq("Titre"), eq("Corps"), any());
        verify(notificationDispatcher).notifyUser(eq(u2), eq("Titre"), eq("Corps"), any());
        verify(notificationDispatcher).notifyUser(eq(u3), eq("Titre"), eq("Corps"), any());
    }

    @Test
    void dispatchCarriesBroadcastTypeAndIdInThePayload() {
        UUID u1 = UUID.randomUUID();
        when(audienceService.page(ALL, 0)).thenReturn(
                new PageImpl<>(List.of(u1), PageRequest.of(0, 200), 1));

        service.dispatchAsync(BROADCAST_ID, "Titre", "Corps", ALL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> payload = ArgumentCaptor.forClass(Map.class);
        verify(notificationDispatcher).notifyUser(eq(u1), anyString(), anyString(), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("type", "ADMIN_BROADCAST")
                .containsEntry("broadcastId", BROADCAST_ID.toString());
    }

    @Test
    void oneFailingRecipientDoesNotAbortTheBroadcast() {
        UUID failing = UUID.randomUUID();
        UUID surviving = UUID.randomUUID();
        when(audienceService.page(ALL, 0)).thenReturn(
                new PageImpl<>(List.of(failing, surviving), PageRequest.of(0, 200), 2));
        doThrow(new IllegalStateException("FCM indisponible"))
                .when(notificationDispatcher).notifyUser(eq(failing), anyString(), anyString(), any());

        service.dispatchAsync(BROADCAST_ID, "Titre", "Corps", ALL);

        verify(notificationDispatcher).notifyUser(eq(surviving), anyString(), anyString(), any());
        verify(notificationDispatcher, times(2)).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void emptyAudienceSendsNothing() {
        when(audienceService.page(ALL, 0)).thenReturn(
                new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

        service.dispatchAsync(BROADCAST_ID, "Titre", "Corps", ALL);

        verify(notificationDispatcher, times(0)).notifyUser(any(), anyString(), anyString(), any());
    }

    /**
     * Le test unitaire ci-dessus appelle {@code dispatchAsync} directement, sans passer par le
     * proxy Spring — il ne peut donc pas prouver que l'appel réel passera bien par l'exécuteur
     * borné plutôt que par le pool {@code applicationTaskExecutor} non borné utilisé par défaut
     * pour tout {@code @Async} sans qualifieur explicite. Cette réflexion sur l'annotation ferme
     * ce trou : elle échoue si quelqu'un retire ou renomme le qualifieur.
     */
    @Test
    void dispatchAsyncIsWiredToTheBoundedBroadcastExecutorQualifier() throws NoSuchMethodException {
        Async async = BroadcastService.class
                .getMethod("dispatchAsync", UUID.class, String.class, String.class, BroadcastTarget.class)
                .getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("broadcastExecutor");
    }
}
