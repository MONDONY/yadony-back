package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.ParcelReturnToSenderRequestedEvent;
import com.yadony.api.cancellation.events.TravelerHighCancellationEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.cancellation.events.TripRescheduleDecidedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.CapacityUnit;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RescheduleDecisionServiceTest {

    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock CancellationRepository cancellationRepository;
    @Mock RematchService rematchService;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    RescheduleDecisionService service;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID rescheduleId = UUID.randomUUID();
    private AnnouncementEntity announcement;
    private BidEntity bid;
    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        service = new RescheduleDecisionService(bidRepository, announcementRepository, userRepository,
                cancellationRepository, rematchService, auditService, eventPublisher);
        UserEntity sender = new UserEntity();
        ReflectionTestUtils.setField(sender, "id", senderId);
        sender.setFirstName("Awa");
        lenient().when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", UUID.randomUUID());
        announcement.setTravelerId(travelerId);
        announcement.setStatus(AnnouncementStatus.FULL);
        announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
        announcement.setAvailableKg(BigDecimal.ZERO);
        announcement.setDepartureDate(LocalDate.now().plusDays(6));
        announcement.setDepartureTime(LocalTime.of(22, 0));
        lenient().when(announcementRepository.findById(announcement.getId())).thenReturn(Optional.of(announcement));

        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(senderId);
        bid.setAnnouncementId(announcement.getId());
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setWeightKg(new BigDecimal("4"));
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setHandoverDeadline(LocalDateTime.now().plusDays(5));
        bid.setPendingRescheduleId(rescheduleId);
        lenient().when(bidRepository.findByIdForUpdate(bidId)).thenReturn(Optional.of(bid));
        lenient().when(cancellationRepository.save(any())).thenAnswer(inv -> {
            CancellationEntity c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    @Test
    void keep_clearsThePendingDecisionAndTellsTheTraveler() {
        service.decide("uid-sender", bidId, RescheduleDecision.KEEP);

        assertThat(bid.getPendingRescheduleId()).isNull();
        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
        verify(eventPublisher).publishEvent(new TripRescheduleDecidedEvent(
                bidId, senderId, travelerId, RescheduleDecision.KEEP, "Awa"));
        verify(auditService).log(eq("BID"), eq(bidId), eq("TRIP_RESCHEDULE_KEPT"), eq(senderId), any());
        verify(cancellationRepository, never()).save(any());
    }

    @Test
    void withdraw_acceptedParcel_refundsFreesTheKilosAndCountsAgainstTheTraveler() {
        UUID rematchCancellationId = UUID.randomUUID();
        when(rematchService.generateForCancellations(eq(announcement), anyList(), anyList()))
                .thenReturn(Map.of(senderId, new RematchService.RematchInfo(rematchCancellationId, 2)));

        service.decide("uid-sender", bidId, RescheduleDecision.WITHDRAW);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getPendingRescheduleId()).isNull();
        assertThat(bid.getReturnCode()).isNull();
        assertThat(announcement.getAvailableKg()).isEqualByComparingTo("4");
        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        assertThat(traveler.getCancellationCount()).isEqualTo(1);

        ArgumentCaptor<CancellationEntity> cancellation = ArgumentCaptor.forClass(CancellationEntity.class);
        verify(cancellationRepository).save(cancellation.capture());
        assertThat(cancellation.getValue().getReason()).isEqualTo("TRIP_RESCHEDULE_WITHDRAWN");
        assertThat(cancellation.getValue().getCancelledBy()).isEqualTo(senderId);

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        TripCancelledEvent refund = (TripCancelledEvent) events.getAllValues().get(0);
        assertThat(refund.getReason()).isEqualTo("TRIP_RESCHEDULE_WITHDRAWN");
        assertThat(refund.getAffectedBidIds()).containsExactly(bidId);
        assertThat(refund.getBidPaymentMethods()).containsEntry(bidId, "STRIPE");
        assertThat(refund.getRematchBySender().get(senderId).suggestionCount()).isEqualTo(2);
        assertThat(refund.getReturnRequiredBidIds()).isEmpty();
        assertThat(events.getAllValues().get(1)).isEqualTo(new TripRescheduleDecidedEvent(
                bidId, senderId, travelerId, RescheduleDecision.WITHDRAW, "Awa"));
    }

    @Test
    void withdraw_handedOverParcel_opensTheSameReturnProcedureAsACancellationAfterHandover() {
        bid.setStatus(BidStatus.HANDED_OVER);

        service.decide("uid-sender", bidId, RescheduleDecision.WITHDRAW);

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getReturnCode()).hasSize(6);
        assertThat(bid.getReturnCodeAttempts()).isZero();
        assertThat(bid.getReturnDeadline()).isAfter(LocalDateTime.now().plusDays(2))
                .isBefore(LocalDateTime.now().plusDays(3).plusMinutes(1));
        assertThat(bid.getReturnCodeExpiry()).isEqualTo(bid.getReturnDeadline());
        // Contact ouvert pendant le retour, fermé à l'échéance.
        assertThat(com.yadony.api.matching.ContactWindow.isOpen(bid, 7, LocalDateTime.now())).isTrue();

        verify(auditService).log(eq("BID"), eq(bidId), eq("RETURN_CODE_GENERATED"), eq(senderId),
                eq(Map.of("returnDeadline", String.valueOf(bid.getReturnDeadline()),
                          "trigger", "TRIP_RESCHEDULE_WITHDRAWN")));
        verify(auditService).log(eq("BID"), eq(bidId), eq("TRIP_RESCHEDULE_WITHDRAWN"), eq(senderId),
                eq(Map.of("rescheduleId", rescheduleId.toString(), "handedOver", "true",
                          "paymentMethod", "STRIPE")));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(3)).publishEvent(events.capture());
        // Remboursement intégral, colis à restituer : PARCEL_RETURN_REQUIRED pour l'expéditeur.
        TripCancelledEvent refund = (TripCancelledEvent) events.getAllValues().get(0);
        assertThat(refund.getReason()).isEqualTo("TRIP_RESCHEDULE_WITHDRAWN");
        assertThat(refund.getAffectedBidIds()).containsExactly(bidId);
        assertThat(refund.getReturnRequiredBidIds()).containsExactly(bidId);
        // Le voyageur rend le colis avant la date (PARCEL_RETURN_TO_SENDER).
        assertThat(events.getAllValues().get(1)).isEqualTo(new ParcelReturnToSenderRequestedEvent(
                bidId, travelerId, senderId, bid.getReturnDeadline().toLocalDate()));
        // Pas de « colis retiré » en doublon : l'événement le signale.
        assertThat(events.getAllValues().get(2)).isEqualTo(new TripRescheduleDecidedEvent(
                bidId, senderId, travelerId, RescheduleDecision.WITHDRAW, "Awa", true));
    }

    @Test
    void withdraw_thirdCancellation_raisesTheHighCancellationAlert() {
        traveler.setCancellationCount(2);

        service.decide("uid-sender", bidId, RescheduleDecision.WITHDRAW);

        verify(eventPublisher).publishEvent(any(TravelerHighCancellationEvent.class));
    }

    @Test
    void withdraw_whenACancellationAlreadyExists_isRefused() {
        when(cancellationRepository.findByBidId(bidId)).thenReturn(Optional.of(new CancellationEntity()));

        assertThatThrownBy(() -> service.decide("uid-sender", bidId, RescheduleDecision.WITHDRAW))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("already-cancelled"));
    }

    @Test
    void decide_byTheTraveler_isForbidden() {
        UserEntity other = new UserEntity();
        ReflectionTestUtils.setField(other, "id", travelerId);
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.decide("uid-traveler", bidId, RescheduleDecision.KEEP))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("forbidden"));
    }

    @Test
    void decide_withoutPendingReschedule_isRefused() {
        bid.setPendingRescheduleId(null);

        assertThatThrownBy(() -> service.decide("uid-sender", bidId, RescheduleDecision.KEEP))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("no-reschedule-pending"));
    }

    @Test
    void decide_afterTheNewHandoverDeadline_isClosed() {
        bid.setHandoverDeadline(LocalDateTime.now().minusHours(1));

        assertThatThrownBy(() -> service.decide("uid-sender", bidId, RescheduleDecision.WITHDRAW))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("reschedule-decision-closed"));
        assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
    }
}
