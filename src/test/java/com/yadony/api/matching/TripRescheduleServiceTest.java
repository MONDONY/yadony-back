package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.TripRescheduleRequest;
import com.yadony.api.matching.dto.TripRescheduleResponse;
import com.yadony.api.matching.events.TripRescheduledEvent;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripRescheduleServiceTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock UserRepository userRepository;
    @Mock TripRescheduleRepository rescheduleRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    TripRescheduleService service;

    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final LocalDate oldDate = LocalDate.now().plusDays(5);
    private final LocalDate newDate = LocalDate.now().plusDays(9);
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        service = new TripRescheduleService(announcementRepository, bidRepository, userRepository,
                rescheduleRepository, auditService, eventPublisher);
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        lenient().when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", announcementId);
        announcement.setTravelerId(travelerId);
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setDepartureDate(oldDate);
        announcement.setDepartureTime(LocalTime.of(22, 0));
        announcement.setHandoverDeadline(oldDate.minusDays(1).atTime(18, 0));
        announcement.setCapacityUnit(CapacityUnit.SUITCASE_23KG);
        announcement.setAvailableKg(BigDecimal.TEN);
        announcement.setPickupAddressLabel("Gare de Lyon");
        lenient().when(announcementRepository.findByIdForUpdate(announcementId)).thenReturn(Optional.of(announcement));
        lenient().when(rescheduleRepository.save(any())).thenAnswer(inv -> {
            TripRescheduleEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            return e;
        });
    }

    private TripRescheduleRequest request(LocalDate date, LocalTime time) {
        return new TripRescheduleRequest(date, time, date.plusDays(1), LocalTime.of(6, 30),
                date.minusDays(1).atTime(18, 0), TripRescheduleReason.FLIGHT_CANCELLED, " Vol annulé par Air Sénégal ");
    }

    private BidEntity bid(BidStatus status) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setAnnouncementId(announcementId);
        bid.setStatus(status);
        return bid;
    }

    @Test
    void reschedule_movesTripAndAsksAcceptedAndHandedOverSendersToDecide() {
        BidEntity accepted = bid(BidStatus.ACCEPTED);
        accepted.setHandoverDeadline(oldDate.minusDays(1).atTime(18, 0));
        accepted.setH2AlertSentAt(LocalDateTime.now());
        BidEntity handedOver = bid(BidStatus.HANDED_OVER);
        handedOver.setConfirmationCode("123456");
        handedOver.setConfirmationCodeExpiry(oldDate.plusDays(2).atStartOfDay());
        BidEntity pending = bid(BidStatus.PENDING);
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(announcementId), anyList()))
                .thenReturn(List.of(accepted, handedOver, pending));

        TripRescheduleResponse response = service.reschedule(announcementId, "uid-traveler",
                request(newDate, LocalTime.of(23, 0)));

        assertThat(announcement.getDepartureDate()).isEqualTo(newDate);
        assertThat(announcement.getArrivalDate()).isEqualTo(newDate.plusDays(1));
        assertThat(announcement.getDepartureAt()).isNotNull();
        assertThat(announcement.getRescheduleCount()).isEqualTo(1);
        assertThat(response.rescheduleCount()).isEqualTo(1);
        assertThat(response.remainingReschedules()).isEqualTo(1);
        assertThat(response.parcelsAwaitingDecision()).isEqualTo(2);
        assertThat(response.requestsInformed()).isEqualTo(1);

        // Colis accepté : nouvelle limite de remise copiée, rappel H-2 réarmé.
        assertThat(accepted.getHandoverDeadline()).isEqualTo(newDate.minusDays(1).atTime(18, 0));
        assertThat(accepted.getH2AlertSentAt()).isNull();
        assertThat(accepted.getPendingRescheduleId()).isEqualTo(response.rescheduleId());
        // Colis remis : le code de retrait expire après la nouvelle arrivée.
        assertThat(handedOver.getConfirmationCodeExpiry()).isEqualTo(newDate.plusDays(2).atTime(6, 30));
        assertThat(handedOver.getPendingRescheduleId()).isEqualTo(response.rescheduleId());
        assertThat(pending.getPendingRescheduleId()).isNull();

        ArgumentCaptor<TripRescheduleEntity> saved = ArgumentCaptor.forClass(TripRescheduleEntity.class);
        verify(rescheduleRepository).save(saved.capture());
        assertThat(saved.getValue().getPreviousDepartureDate()).isEqualTo(oldDate);
        assertThat(saved.getValue().getNewDepartureDate()).isEqualTo(newDate);
        assertThat(saved.getValue().getNote()).isEqualTo("Vol annulé par Air Sénégal");

        ArgumentCaptor<TripRescheduledEvent> event = ArgumentCaptor.forClass(TripRescheduledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reason()).isEqualTo("FLIGHT_CANCELLED");
        assertThat(event.getValue().targets())
                .extracting(TripRescheduledEvent.Target::decisionRequired)
                .containsExactly(true, true, false);
        verify(auditService).log(eq("ANNOUNCEMENT"), eq(announcementId), eq("TRIP_RESCHEDULED"), eq(travelerId), any());
    }

    @Test
    void reschedule_tripMarkedInProgressComesBackOnTheMarket() {
        announcement.setStatus(AnnouncementStatus.IN_PROGRESS);
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(List.of());

        service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0)));

        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }

    @Test
    void reschedule_tripInProgressWithoutRoomLeftComesBackFull() {
        announcement.setStatus(AnnouncementStatus.IN_PROGRESS);
        announcement.setAvailableKg(BigDecimal.ZERO);
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(List.of());

        service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0)));

        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.FULL);
    }

    @Test
    void reschedule_byAnotherUser_isForbidden() {
        UserEntity other = new UserEntity();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-other", request(newDate, LocalTime.of(23, 0))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("forbidden"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void reschedule_thirdTime_isRefused() {
        announcement.setRescheduleCount(2);

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("reschedule-limit-reached"));
    }

    @Test
    void reschedule_withParcelAlreadyInTransit_isRefused() {
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(true);

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("reschedule-in-transit"));
    }

    @Test
    void reschedule_isRefusedOnceAParcelIsOnItsWayArrivedOrDelivered() {
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId),
                argThat((List<BidStatus> statuses) -> statuses.containsAll(
                        List.of(BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED))
                        && !statuses.contains(BidStatus.HANDED_OVER))))
                .thenReturn(true);

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("reschedule-in-transit"));
    }

    @Test
    void reschedule_cancelledTrip_isRefused() {
        announcement.setStatus(AnnouncementStatus.CANCELLED);

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("reschedule-invalid-status"));
    }

    @Test
    void reschedule_toTheSameDateAndTime_isRefused() {
        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", request(oldDate, LocalTime.of(22, 0))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("reschedule-same-date"));
    }

    @Test
    void reschedule_toThePast_isRefused() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", request(yesterday, LocalTime.of(10, 0))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("invalid-departure-date"));
    }

    @Test
    void reschedule_withHandoverDeadlineAlreadyPassed_isRefused() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        var req = new TripRescheduleRequest(tomorrow, LocalTime.of(23, 0), null, null,
                LocalDateTime.now().minusHours(2), TripRescheduleReason.POSTPONED, null);

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", req))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("handover-deadline-past"));
    }
}
