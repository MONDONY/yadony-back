package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests de {@link SenderReputationListener} (tranche E — réputation expéditeur D6/D8). */
@ExtendWith(MockitoExtension.class)
@DisplayName("SenderReputationListener")
class SenderReputationListenerTest {

    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    @InjectMocks private SenderReputationListener listener;

    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID CANCELLATION_ID = UUID.randomUUID();

    private BidEntity bid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", BID_ID);
        ReflectionTestUtils.setField(b, "senderId", SENDER_ID);
        return b;
    }

    private UserEntity sender(int count) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", SENDER_ID);
        u.setSenderHandoverIncidentCount(count);
        return u;
    }

    @Test
    @DisplayName("no-show confirmé → incrémente le compteur d'incidents + audit")
    void senderNoShow_incrementsAndAudits() {
        UserEntity sender = sender(0);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid()));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(BID_ID, CANCELLATION_ID, CancellationReason.SENDER_NO_SHOW));

        ArgumentCaptor<UserEntity> uCap = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(uCap.capture());
        assertThat(uCap.getValue().getSenderHandoverIncidentCount()).isEqualTo(1);
        verify(auditService).log(eq("USER"), eq(SENDER_ID), eq("SENDER_REPUTATION_INCIDENT"), any(), any());
    }

    @Test
    @DisplayName("no-show confirmé sur compteur déjà > 0 → incrément cumulatif")
    void senderNoShow_accumulates() {
        UserEntity sender = sender(2);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(bid()));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(BID_ID, CANCELLATION_ID, CancellationReason.SENDER_NO_SHOW));

        assertThat(sender.getSenderHandoverIncidentCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("autre motif (pas no-show) → ignoré, aucune mutation")
    void otherReason_ignored() {
        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(BID_ID, CANCELLATION_ID,
                        CancellationReason.TRAVELER_CANCEL_AFTER_HANDOVER));

        verify(bidRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("bid introuvable → no-op (pas de crash)")
    void bidMissing_noop() {
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.empty());

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(BID_ID, CANCELLATION_ID, CancellationReason.SENDER_NO_SHOW));

        verify(userRepository, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.yadony.api.matching.BidStatus.class,
            names = {"HANDED_OVER", "IN_TRANSIT", "ARRIVED", "COMPLETED", "PARCEL_REFUSED"})
    @DisplayName("colis déjà remis → l'expéditeur est venu, aucune pénalité")
    void parcelAlreadyHandedOver_noPenalty(com.yadony.api.matching.BidStatus handedOver) {
        BidEntity b = bid();
        b.setStatus(handedOver);
        when(bidRepository.findById(BID_ID)).thenReturn(Optional.of(b));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(BID_ID, CANCELLATION_ID, CancellationReason.SENDER_NO_SHOW));

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), any());
    }
}
