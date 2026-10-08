package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.TravelerHighCancellationEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidCancelledByParticipantEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Fiabilité sur annulation d'un colis à l'unité (FLUTTER-E4/E0/E6). */
@ExtendWith(MockitoExtension.class)
@DisplayName("BidCancellationReputationListener")
class BidCancellationReputationListenerTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private BidCancellationReputationListener listener;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private UserEntity actor() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", ACTOR_ID);
        return u;
    }

    private BidCancelledByParticipantEvent event(boolean byTraveler, BidStatus previous, PaymentMethod method) {
        return new BidCancelledByParticipantEvent(BID_ID, ANNOUNCEMENT_ID, ACTOR_ID, byTraveler, previous, method);
    }

    @ParameterizedTest(name = "{0}/{1} → accepté={2}")
    @CsvSource({
            "ACCEPTED, CASH, true",
            "HANDED_OVER, CASH, true",
            "AWAITING_PAYMENT, MOBILE_MONEY, true",
            "AWAITING_PAYMENT, STRIPE, false",
            "PAYMENT_ESCROWED, STRIPE, false",
            "PENDING, CASH, false",
            "NEGOTIATING, CASH, false"
    })
    void acceptedPredicate(BidStatus status, PaymentMethod method, boolean expected) {
        assertThat(BidCancellationReputationListener.wasAcceptedByTraveler(status, method)).isEqualTo(expected);
    }

    @Test
    void nullPreviousStatus_isNotAccepted() {
        assertThat(BidCancellationReputationListener.wasAcceptedByTraveler(null, PaymentMethod.CASH)).isFalse();
    }

    @Test
    @DisplayName("voyageur annule un colis accepté → cancellationCount +1 et audit")
    void travelerCancelsAccepted_incrementsCancellationCount() {
        UserEntity traveler = actor();
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(traveler));

        listener.onBidCancelled(event(true, BidStatus.ACCEPTED, PaymentMethod.CASH));

        assertThat(traveler.getCancellationCount()).isEqualTo(1);
        assertThat(traveler.getSenderCancellationCount()).isZero();
        verify(userRepository).save(traveler);
        verify(auditService).log("BID", BID_ID, "BID_CANCELLED_BY_TRAVELER_COUNTED", ACTOR_ID,
                Map.of("previousStatus", "ACCEPTED", "cancellationCount", "1"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("3e annulation du voyageur → alerte admin, comme cancelTrip")
    void travelerThirdCancellation_raisesHighCancellationAlert() {
        UserEntity traveler = actor();
        traveler.setCancellationCount(2);
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(traveler));

        listener.onBidCancelled(event(true, BidStatus.ACCEPTED, PaymentMethod.CASH));

        verify(auditService).log(eq("USER"), eq(ACTOR_ID), eq("HIGH_CANCELLATION_ALERT"), eq(ACTOR_ID), any());
        verify(eventPublisher).publishEvent(any(TravelerHighCancellationEvent.class));
    }

    @Test
    @DisplayName("expéditeur annule un colis accepté → senderCancellationCount +1 et audit")
    void senderCancelsAccepted_incrementsSenderCounter() {
        UserEntity sender = actor();
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(sender));

        listener.onBidCancelled(event(false, BidStatus.AWAITING_PAYMENT, PaymentMethod.MOBILE_MONEY));

        assertThat(sender.getSenderCancellationCount()).isEqualTo(1);
        assertThat(sender.getCancellationCount()).isZero();
        assertThat(sender.senderReliabilityIncidentCount()).isEqualTo(1);
        verify(auditService).log(eq("USER"), eq(ACTOR_ID), eq("SENDER_CANCELLATION_COUNTED"), eq(ACTOR_ID), any());
    }

    @Test
    @DisplayName("annulation avant acceptation → rien n'est compté")
    void cancelBeforeAcceptance_countsNothing() {
        listener.onBidCancelled(event(false, BidStatus.PENDING, PaymentMethod.CASH));
        listener.onBidCancelled(event(true, BidStatus.PAYMENT_ESCROWED, PaymentMethod.STRIPE));

        verifyNoInteractions(userRepository, auditService, eventPublisher);
    }

    @Test
    void missingActor_isIgnored() {
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.empty());

        listener.onBidCancelled(event(true, BidStatus.ACCEPTED, PaymentMethod.CASH));

        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), any());
    }
}
