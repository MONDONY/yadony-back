package com.yadony.api.notifications;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.signalements.events.ReportResolvedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportResolvedNotificationListenerTest {

    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private ReportResolvedNotificationListener listener;

    private final UUID reporterId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();

    @Test
    void francais_sobreSansDetailDeSanction() {
        when(notificationDispatcher.messagesFor(reporterId)).thenReturn(TestMessages.fr());

        listener.onResolved(new ReportResolvedEvent(reportId, reporterId));

        verify(notificationDispatcher).notifyUser(eq(reporterId), eq("Signalement traité"),
                eq("Merci, votre signalement a été traité."),
                eq(Map.of("type", "REPORT_RESOLVED")));
    }

    @Test
    void anglais() {
        when(notificationDispatcher.messagesFor(reporterId)).thenReturn(TestMessages.en());

        listener.onResolved(new ReportResolvedEvent(reportId, reporterId));

        verify(notificationDispatcher).notifyUser(eq(reporterId), eq("Report reviewed"),
                eq("Thank you, your report has been reviewed."),
                eq(Map.of("type", "REPORT_RESOLVED")));
    }

    @Test
    void signalantAbsent_rienNEstEnvoye() {
        listener.onResolved(new ReportResolvedEvent(reportId, null));

        verifyNoInteractions(notificationDispatcher);
    }

    @Test
    void rangeeDansLesAnnoncesDuFil_sansLienProfond() {
        assertThat(NotificationCategory.fromType("REPORT_RESOLVED")).isEqualTo(NotificationCategory.ANNONCE);
        assertThat(NotificationDeeplink.of("REPORT_RESOLVED", Map.of("type", "REPORT_RESOLVED"))).isEmpty();
    }
}
