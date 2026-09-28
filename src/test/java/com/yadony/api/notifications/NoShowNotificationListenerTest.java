package com.yadony.api.notifications;

import com.yadony.api.cancellation.CancellationScope;
import com.yadony.api.cancellation.NoShowAdminDecision;
import com.yadony.api.cancellation.events.NoShowAdminDecisionEvent;
import com.yadony.api.cancellation.events.SenderNoShowReportedEvent;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoShowNotificationListenerTest {

    @Mock NotificationDispatcher dispatcher;

    @InjectMocks NoShowNotificationListener listener;

    final UUID bidId = UUID.randomUUID();
    final UUID cancellationId = UUID.randomUUID();
    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();

    private NoShowAdminDecisionEvent decision(NoShowAdminDecision d, CancellationScope scope, UUID traveler) {
        return new NoShowAdminDecisionEvent(cancellationId, bidId, scope, "SENDER_NO_SHOW", d, senderId, traveler,
                UUID.randomUUID(), List.of());
    }

    @Test
    void expediteurDeclareAbsent_estPrevenuAvecLeDelai() {
        when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.fr());

        listener.onSenderNoShowReported(new SenderNoShowReportedEvent(bidId, cancellationId, senderId, travelerId, 24));

        verify(dispatcher).notifyUser(senderId, "Absence à la remise",
                "Le voyageur vous déclare absent à la remise. Contestez sous 24 h.",
                Map.of("type", "SENDER_NOSHOW_REPORTED", "bidId", bidId.toString(),
                        "cancellationId", cancellationId.toString()));
        verify(dispatcher, never()).notifyUser(eq(travelerId), anyString(), anyString(), any());
    }

    @Test
    void expediteurDeclareAbsent_anglais() {
        when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.en());

        listener.onSenderNoShowReported(new SenderNoShowReportedEvent(bidId, cancellationId, senderId, travelerId, 24));

        verify(dispatcher).notifyUser(eq(senderId), eq("Marked absent at handover"),
                eq("The traveler marked you absent at handover. Contest within 24 h."), any());
    }

    @Test
    void confirmationRemise_lesDeuxPartiesPrevenues_sansMotifInterne() {
        when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());

        listener.onDecision(decision(NoShowAdminDecision.CONFIRMED, CancellationScope.HANDOVER, travelerId));

        Map<String, String> data = Map.of("type", "NOSHOW_DECISION", "bidId", bidId.toString(),
                "cancellationId", cancellationId.toString(), "decision", "CONFIRMED");
        String body = "Yadony a confirmé l'absence à la remise. L'envoi est annulé.";
        verify(dispatcher).notifyUser(senderId, "Absence confirmée", body, data);
        verify(dispatcher).notifyUser(travelerId, "Absence confirmée", body, data);
    }

    @Test
    void confirmationLivraison_annonceLeLitige() {
        when(dispatcher.messagesFor(any())).thenReturn(TestMessages.en());

        listener.onDecision(decision(NoShowAdminDecision.CONFIRMED, CancellationScope.DELIVERY, travelerId));

        verify(dispatcher, times(2)).notifyUser(any(), eq("No-show confirmed"),
                eq("Yadony confirmed the missed delivery. A dispute is now open."), any());
    }

    @Test
    void rejet_voyageurInconnu_seulLExpediteurEstPrevenu() {
        when(dispatcher.messagesFor(senderId)).thenReturn(TestMessages.fr());

        listener.onDecision(decision(NoShowAdminDecision.REJECTED, CancellationScope.HANDOVER, null));

        verify(dispatcher).notifyUser(eq(senderId), eq("Signalement rejeté"),
                eq("Yadony a rejeté le signalement d'absence. L'envoi suit son cours."), any());
        verify(dispatcher, times(1)).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void expediteurAbsent_null_rienNEstEnvoye() {
        listener.onSenderNoShowReported(new SenderNoShowReportedEvent(bidId, cancellationId, null, travelerId, 24));

        verifyNoInteractions(dispatcher);
    }

    @Test
    void lienProfond_versLeColis() {
        Map<String, String> data = Map.of("bidId", bidId.toString());
        assertThat(NotificationDeeplink.of("SENDER_NOSHOW_REPORTED", data)).contains("yadony://bids/" + bidId);
        assertThat(NotificationDeeplink.of("NOSHOW_DECISION", data)).contains("yadony://bids/" + bidId);
        assertThat(NotificationCategory.fromType("NOSHOW_DECISION")).isEqualTo(NotificationCategory.COLIS);
    }
}
