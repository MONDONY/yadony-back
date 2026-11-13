package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.events.TripDelayedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Retard annoncé (BA6) : texte, push et e-mail, FR et EN, sans SMS de repli (pas de décision à prendre). */
class TripDelayNotificationTest {

    private final FcmService fcm = mock(FcmService.class);
    private final SmsService sms = mock(SmsService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final NotificationEmailService emails = mock(NotificationEmailService.class);

    private final UUID senderA = UUID.randomUUID();
    private final UUID senderB = UUID.randomUUID();
    private final UUID bidA = UUID.randomUUID();
    private final UUID bidB = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 10, 13);
    private final LocalDate to = LocalDate.of(2026, 10, 20);

    @BeforeEach
    void setUp() {
        NotificationEntity saved = new NotificationEntity(senderA, "X", "t", "b", Map.of(), false);
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
        when(notifications.persist(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(saved);
    }

    private NotificationDispatcher dispatcher(AppLanguage language) {
        return new NotificationDispatcher(fcm, sms, mock(UserRepository.class), notifications,
                mock(BlockVisibility.class), null, TestMessages.resolver(language), emails);
    }

    private TripDelayedEvent event(LocalDate arrival, LocalDate arrivalMax) {
        return new TripDelayedEvent(announcementId, UUID.randomUUID(), UUID.randomUUID(), "PORT_CONGESTION",
                arrival, arrivalMax, "Quai saturé",
                List.of(new TripDelayedEvent.Target(bidA, senderA), new TripDelayedEvent.Target(bidB, senderB)));
    }

    @Test
    void texts_rangeAndSingleDay_frAndEn() {
        var fr = NotificationTexts.tripDelayed(TestMessages.fr(), from, to);
        assertThat(fr.title()).isEqualTo("Retard annoncé");
        assertThat(fr.body()).isEqualTo("Nouvelle arrivée estimée du 13 oct. au 20 oct.");
        var en = NotificationTexts.tripDelayed(TestMessages.en(), from, to);
        assertThat(en.title()).isEqualTo("Delay announced");
        assertThat(en.body()).isEqualTo("New estimated arrival: Oct 13 to Oct 20.");

        assertThat(NotificationTexts.tripDelayed(TestMessages.fr(), from, from).body())
                .isEqualTo("Nouvelle arrivée estimée le 13 oct.");
        assertThat(NotificationTexts.tripDelayed(TestMessages.en(), from, from).body())
                .isEqualTo("New estimated arrival: Oct 13.");
    }

    @Test
    void texts_fitTheNotificationCaps() {
        for (var m : List.of(TestMessages.fr(), TestMessages.en())) {
            for (LocalDate d : List.of(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 12, 28))) {
                var text = NotificationTexts.tripDelayed(m, d, d.plusDays(10));
                assertThat(text.title().length()).isLessThanOrEqualTo(NotificationCaps.TITLE_MAX);
                assertThat(text.body().length()).isLessThanOrEqualTo(NotificationCaps.BODY_MAX);
            }
        }
    }

    @Test
    void dispatcher_pushesAndPersistsOnePerParcelSender_withBidAndAnnouncement() {
        dispatcher(AppLanguage.FR).onTripDelayed(event(from, to));

        verify(notifications).persist(eq(senderA), eq("TRIP_DELAYED"), eq("Retard annoncé"),
                eq("Nouvelle arrivée estimée du 13 oct. au 20 oct."), anyMap(), eq(false));
        verify(notifications).persist(eq(senderB), eq("TRIP_DELAYED"), eq("Retard annoncé"),
                eq("Nouvelle arrivée estimée du 13 oct. au 20 oct."), anyMap(), eq(false));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(fcm).sendToUser(eq(senderA), eq("Retard annoncé"),
                eq("Nouvelle arrivée estimée du 13 oct. au 20 oct."), data.capture());
        assertThat(data.getValue()).containsEntry("type", "TRIP_DELAYED")
                .containsEntry("bidId", bidA.toString())
                .containsEntry("announcementId", announcementId.toString());
        verify(fcm).sendToUser(eq(senderB), any(), any(), any());
    }

    @Test
    void dispatcher_inEnglish() {
        dispatcher(AppLanguage.EN).onTripDelayed(event(from, to));

        verify(fcm).sendToUser(eq(senderA), eq("Delay announced"),
                eq("New estimated arrival: Oct 13 to Oct 20."), anyMap());
    }

    @Test
    void dispatcher_sendsTheEmailToo_inTheRecipientsLanguage() {
        dispatcher(AppLanguage.EN).onTripDelayed(event(from, to));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(emails).send(eq(senderA), eq("TRIP_DELAYED"), eq("Delay announced"),
                eq("New estimated arrival: Oct 13 to Oct 20."), data.capture());
        assertThat(data.getValue()).containsEntry("bidId", bidA.toString());
        verify(emails).send(eq(senderB), eq("TRIP_DELAYED"), any(), any(), any());
    }

    @Test
    void notCritical_noSmsFallback() {
        dispatcher(AppLanguage.FR).onTripDelayed(event(from, to));

        assertThat(NotificationTypes.isCritical("TRIP_DELAYED")).isFalse();
        verifyNoInteractions(sms);
    }

    @Test
    void noTargets_noNotification() {
        dispatcher(AppLanguage.FR).onTripDelayed(new TripDelayedEvent(announcementId, UUID.randomUUID(),
                UUID.randomUUID(), "WEATHER", from, to, null, List.of()));

        verify(fcm, never()).sendToUser(any(), any(), any(), any());
        verify(emails, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void emailPolicy_isAParcelRecapWithItsPushKept() {
        assertThat(NotificationEmailPolicy.kindOf("TRIP_DELAYED", Map.of()))
                .contains(NotificationEmailPolicy.Kind.RECAP);
        assertThat(NotificationEmailPolicy.replacesPush("TRIP_DELAYED", Map.of())).isFalse();
    }

    @Test
    void deeplinkOpensTheParcel_andPushPrefFollowsParcelActivity() {
        assertThat(NotificationDeeplink.of("TRIP_DELAYED", Map.of("bidId", bidA.toString())))
                .contains("yadony://bids/" + bidA);
        assertThat(NotificationPrefsService.TYPE_TO_PREF).containsEntry("TRIP_DELAYED", "pushActivityBids");
        assertThat(NotificationPrefsService.isAlwaysOn("TRIP_DELAYED")).isFalse();
    }

    @Test
    void feedFilesItWithTheParcels() {
        assertThat(NotificationCategory.fromType("TRIP_DELAYED")).isEqualTo(NotificationCategory.COLIS);
    }
}
