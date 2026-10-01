package com.yadony.api.notifications;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.RescheduleDecision;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.cancellation.events.TripRescheduleDecidedEvent;
import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.events.TripRescheduledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Report de trajet : qui est prévenu, avec quelle urgence, et dans quelle langue. */
@ExtendWith(MockitoExtension.class)
class NotificationDispatcherRescheduleTest {

    @Mock FcmService fcmService;
    @Mock SmsService smsService;
    @Mock UserRepository userRepository;
    @Mock NotificationService notificationService;
    @Mock com.yadony.api.common.BlockVisibility blockVisibility;

    private final com.yadony.api.payments.pawapay.PawapayProperties pawapayProperties =
            new com.yadony.api.payments.pawapay.PawapayProperties(true, "https://x", "t", false, 30,
                    "https://api.test", "yadony://bids/%s/mobile-money/awaiting",
                    "yadony://negotiations/%s/mobile-money/awaiting", "yadony://payments/wallet",
                    new com.yadony.api.payments.pawapay.PawapayProperties.BalanceMin(
                            java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));

    private final UUID senderId = UUID.randomUUID();
    private final UUID otherSenderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID otherBidId = UUID.randomUUID();
    private final LocalDate newDate = LocalDate.of(2026, 10, 13);

    NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = dispatcherIn(AppLanguage.FR);
        var stub = new NotificationEntity(UUID.randomUUID(), "STUB", "stub", "stub", Map.of(), false);
        ReflectionTestUtils.setField(stub, "id", UUID.randomUUID());
        lenient().when(notificationService.persist(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(stub);
    }

    private NotificationDispatcher dispatcherIn(AppLanguage language) {
        return new NotificationDispatcher(fcmService, smsService, userRepository, notificationService,
                blockVisibility, pawapayProperties, TestMessages.resolver(language));
    }

    private TripRescheduledEvent event() {
        return new TripRescheduledEvent(UUID.randomUUID(), UUID.randomUUID(), travelerId, "FLIGHT_CANCELLED",
                newDate.minusDays(4), newDate, LocalTime.of(22, 0),
                List.of(new TripRescheduledEvent.Target(bidId, senderId, true),
                        new TripRescheduledEvent.Target(otherBidId, otherSenderId, false)));
    }

    @Test
    void onTripRescheduled_engagedParcelIsCritical_pendingRequestIsInformed() {
        dispatcher.onTripRescheduled(event());

        verify(notificationService).persist(eq(senderId), eq("TRIP_RESCHEDULED"), eq("Trajet reporté"),
                eq("Le voyageur a reporté son trajet au mardi 13 octobre (vol annulé). "
                        + "Gardez votre colis sur la nouvelle date ou annulez sans frais."),
                anyMap(), eq(true));
        verify(notificationService).persist(eq(otherSenderId), eq("TRIP_RESCHEDULED"), eq("Trajet reporté"),
                eq("Le voyageur a reporté son trajet au mardi 13 octobre (vol annulé). Votre demande reste valable."),
                anyMap(), eq(false));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(fcmService).sendToUser(eq(senderId), any(), any(), data.capture());
        assertThat(data.getValue()).containsEntry("type", "TRIP_RESCHEDULED").containsEntry("bidId", bidId.toString());
    }

    @Test
    void onTripRescheduled_inEnglish() {
        dispatcherIn(AppLanguage.EN).onTripRescheduled(event());

        verify(fcmService).sendToUser(eq(senderId), eq("Trip rescheduled"),
                eq("The traveler moved their trip to Tuesday, October 13 (flight canceled). "
                        + "Keep your parcel on the new date or cancel at no cost."), anyMap());
    }

    @Test
    void onTripRescheduleDecided_tellsTheTraveler() {
        dispatcher.onTripRescheduleDecided(new TripRescheduleDecidedEvent(bidId, senderId, travelerId,
                RescheduleDecision.KEEP));
        dispatcher.onTripRescheduleDecided(new TripRescheduleDecidedEvent(otherBidId, senderId, travelerId,
                RescheduleDecision.WITHDRAW));

        verify(notificationService).persist(eq(travelerId), eq("TRIP_RESCHEDULE_KEPT"), eq("Colis maintenu"),
                eq("L'expéditeur garde son colis sur la nouvelle date de votre trajet."), anyMap(), eq(false));
        verify(notificationService).persist(eq(travelerId), eq("TRIP_RESCHEDULE_WITHDRAWN"), eq("Colis retiré"),
                eq("L'expéditeur a retiré son colis après le report de votre trajet. Il est remboursé."),
                anyMap(), eq(false));
    }

    @Test
    void onTripCancelled_afterAWithdrawal_staysSilent() {
        dispatcher.onTripCancelled(new TripCancelledEvent(UUID.randomUUID(), travelerId, List.of(senderId),
                "TRIP_RESCHEDULE_WITHDRAWN", List.of(bidId)));

        verifyNoInteractions(fcmService, notificationService);
    }
}
