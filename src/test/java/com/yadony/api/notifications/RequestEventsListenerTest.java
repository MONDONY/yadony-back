package com.yadony.api.notifications;

import com.yadony.api.requests.event.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestEventsListenerTest {

    @Mock private NotificationDispatcher dispatcher;
    @InjectMocks private RequestEventsListener listener;

    @Test
    void onNegotiationStarted_notifiesSender() {
        UUID senderId = UUID.randomUUID();
        var event = new NegotiationStartedEvent(
            UUID.randomUUID(), UUID.randomUUID(), senderId, UUID.randomUUID(),
            new BigDecimal("30")
        );

        listener.onNegotiationStarted(event);

        verify(dispatcher).notifyUser(eq(senderId), contains("proposition"), anyString(), anyMap());
    }

    @Test
    void onNegotiationCounterPosted_notifiesToUser() {
        UUID toUserId = UUID.randomUUID();
        var event = new NegotiationCounterPostedEvent(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), toUserId,
            new BigDecimal("25"), 2
        );

        listener.onNegotiationCounterPosted(event);

        verify(dispatcher).notifyUser(eq(toUserId), contains("contre-proposition"), anyString(), anyMap());
    }

    @Test
    void onPackageRequestAccepted_notifiesBothParties() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        var event = new PackageRequestAcceptedEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            senderId, travelerId, new BigDecimal("30"), null,
            new BigDecimal("5"), "test colis", "vetements", "pi_test_123",
            "Fatou Diop", "+221771234567",
            java.time.LocalDateTime.now(), "1.2.3.4",
            com.yadony.api.payments.cash.PaymentMethod.STRIPE
        , java.util.List.of(), null, null, null);

        listener.onPackageRequestAccepted(event);

        // Le voyageur garde son push : nouvelle information, et une action suit (préparer le
        // retrait). L'expéditeur vient de confirmer ce paiement dans l'application, son écran
        // de succès le lui a déjà dit — in-app suffit.
        verify(dispatcher).notifyUser(eq(travelerId), contains("Paiement reçu"), anyString(), anyMap());
        verify(dispatcher).notifyUser(eq(senderId), contains("finalisée"), anyString(), anyMap(), eq(false));
        verify(dispatcher, never()).notifyUser(eq(senderId), anyString(), anyString(), anyMap());
    }

    @Test
    void onNegotiationAwaitingTrip_notifiesTraveler() {
        UUID travelerId = UUID.randomUUID();
        var event = new com.yadony.api.requests.event.NegotiationAwaitingTripEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), travelerId, new BigDecimal("30")
        );

        listener.onNegotiationAwaitingTrip(event);

        verify(dispatcher).notifyUser(eq(travelerId), contains("acceptée"), anyString(), anyMap());
    }

    @Test
    void onNegotiationAwaitingPayment_notifiesSender() {
        UUID senderId = UUID.randomUUID();
        var event = new com.yadony.api.requests.event.NegotiationAwaitingPaymentEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            senderId, UUID.randomUUID(), new BigDecimal("30"), UUID.randomUUID()
        );

        listener.onNegotiationAwaitingPayment(event);

        verify(dispatcher).notifyUser(eq(senderId), contains("paiement"), anyString(), anyMap());
    }

    @Test
    void onNegotiationCommissionPending_notifiesTravelerWithDeadline() {
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID packageRequestId = UUID.randomUUID();
        var expiresAt = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(120);
        var event = new NegotiationCommissionPendingEvent(
            threadId, packageRequestId, travelerId, UUID.randomUUID(),
            new BigDecimal("4.20"), "EUR", expiresAt
        );

        listener.onNegotiationCommissionPending(event);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, String>> dataCaptor =
            (ArgumentCaptor<java.util.Map<String, String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(java.util.Map.class);
        verify(dispatcher).notifyUser(eq(travelerId), contains("Confirmez"),
            bodyCaptor.capture(), dataCaptor.capture());
        // Message mentionnant la commission, sans tiret cadratin (règle copie FR).
        assertThat(bodyCaptor.getValue()).contains("commission").doesNotContain("—");
        assertThat(dataCaptor.getValue())
            .containsEntry("type", "negotiation_commission_pending")
            .containsEntry("threadId", threadId.toString())
            .containsEntry("packageRequestId", packageRequestId.toString());
    }

    @Test
    void onNegotiationCommissionDeclined_notifiesSenderOnly() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        var event = new NegotiationCommissionDeclinedEvent(
            threadId, UUID.randomUUID(), senderId, travelerId
        );

        listener.onNegotiationCommissionDeclined(event);

        verify(dispatcher).notifyUser(eq(senderId), anyString(), contains("disponible"), anyMap());
        verify(dispatcher, never()).notifyUser(eq(travelerId), anyString(), anyString(), anyMap());
    }

    @Test
    void onNegotiationCommissionExpired_notifiesBothPartiesInAppOnly() {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        var event = new NegotiationCommissionExpiredEvent(
            UUID.randomUUID(), UUID.randomUUID(), senderId, travelerId
        );

        listener.onNegotiationCommissionExpired(event);

        verify(dispatcher).notifyUser(eq(travelerId), anyString(), anyString(), anyMap(), eq(false));
        // L'expéditeur doit apprendre que sa demande repart à d'autres voyageurs :
        // c'est le corps du message qui le dit, le titre porte « disponible ».
        verify(dispatcher).notifyUser(eq(senderId), contains("disponible"), contains("ouverte"),
            anyMap(), eq(false));
        verify(dispatcher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void onPackageRequestExpired_notifiesSender() {
        UUID senderId = UUID.randomUUID();
        var event = new PackageRequestExpiredEvent(UUID.randomUUID(), senderId);

        listener.onPackageRequestExpired(event);

        verify(dispatcher).notifyUser(eq(senderId), contains("expiré"), anyString(), anyMap());
    }

    @Test
    void onNegotiationExpired_notifiesTraveler_evenWithNullSender() {
        UUID travelerId = UUID.randomUUID();
        var event = new NegotiationExpiredEvent(
            UUID.randomUUID(), UUID.randomUUID(), null, travelerId
        );

        listener.onNegotiationExpired(event);

        // In-app seulement : une expiration est l'absence d'événement, il n'y a rien à faire.
        verify(dispatcher).notifyUser(eq(travelerId), anyString(), anyString(), anyMap(), eq(false));
        verifyNoMoreInteractions(dispatcher);
    }

    @Test
    void onNegotiationExpired_notifiesBothParties_whenSenderPresent() {
        UUID travelerId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        var event = new NegotiationExpiredEvent(
            UUID.randomUUID(), UUID.randomUUID(), senderId, travelerId
        );

        listener.onNegotiationExpired(event);

        verify(dispatcher).notifyUser(eq(travelerId), anyString(), anyString(), anyMap(), eq(false));
        verify(dispatcher).notifyUser(eq(senderId), anyString(), anyString(), anyMap(), eq(false));
        verify(dispatcher, never()).notifyUser(any(), anyString(), anyString(), anyMap());
    }

    @Test
    void onPackageRequestCreated_logsButDoesNotDispatch() {
        var event = new PackageRequestCreatedEvent(
            UUID.randomUUID(), UUID.randomUUID(),
            "Paris", "Dakar", LocalDate.now().plusDays(7)
        );

        listener.onPackageRequestCreated(event);

        verifyNoInteractions(dispatcher);
    }
}
