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
    void reschedule_softDeletesTheAnnouncedDelays_soLatestTripDelayGoesStale() {
        TripDelayRepository delayRepository = org.mockito.Mockito.mock(TripDelayRepository.class);
        service.setTripDelayRepository(delayRepository);
        TripDelayEntity d1 = new TripDelayEntity();
        TripDelayEntity d2 = new TripDelayEntity();
        when(delayRepository.findByAnnouncementId(announcementId)).thenReturn(List.of(d1, d2));
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(List.of());

        service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0)));

        assertThat(d1.getDeletedAt()).isNotNull();
        assertThat(d2.getDeletedAt()).isNotNull();
        org.mockito.Mockito.verify(delayRepository).save(d1);
        org.mockito.Mockito.verify(delayRepository).save(d2);
    }

    @Test
    void reschedule_withoutDelayRepository_stillWorks() {
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(List.of());

        service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0)));

        assertThat(announcement.getDepartureDate()).isEqualTo(newDate);
    }

    @Test
    void rescheduleResetsDepartedAt() {
        announcement.setDepartedAt(java.time.OffsetDateTime.now().minusHours(1));
        announcement.setDeparturePromptSentAt(java.time.OffsetDateTime.now().minusHours(1));
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(List.of());

        service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0)));

        assertThat(announcement.getDepartedAt()).isNull();
        assertThat(announcement.getDeparturePromptSentAt()).isNull();
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
    void reschedule_depositPaidParcel_isOnlyInformed_noWithdrawOffered() {
        BidEntity paidAtDeposit = bid(BidStatus.HANDED_OVER);
        org.springframework.test.util.ReflectionTestUtils.setField(paidAtDeposit, "payoutTrigger", PayoutTrigger.DEPOSIT);
        org.springframework.test.util.ReflectionTestUtils.setField(paidAtDeposit, "payoutTriggerFrozenAt",
                LocalDateTime.now().minusDays(2));
        BidEntity depositedNotPaid = bid(BidStatus.HANDED_OVER);
        org.springframework.test.util.ReflectionTestUtils.setField(depositedNotPaid, "payoutTrigger",
                PayoutTrigger.DEPOSIT);
        org.springframework.test.util.ReflectionTestUtils.setField(depositedNotPaid, "payoutTriggerFrozenAt",
                LocalDateTime.now().minusDays(2));
        service.setDepositPayoutPort(id -> id.equals(paidAtDeposit.getId()));
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(announcementId), anyList()))
                .thenReturn(List.of(paidAtDeposit, depositedNotPaid));

        TripRescheduleResponse response = service.reschedule(announcementId, "uid-traveler",
                request(newDate, LocalTime.of(23, 0)));

        assertThat(response.parcelsAwaitingDecision()).isEqualTo(1);
        assertThat(paidAtDeposit.getPendingRescheduleId()).isNull();
        assertThat(depositedNotPaid.getPendingRescheduleId()).isEqualTo(response.rescheduleId());
        ArgumentCaptor<TripRescheduledEvent> event = ArgumentCaptor.forClass(TripRescheduledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().targets())
                .extracting(TripRescheduledEvent.Target::decisionRequired)
                .containsExactly(false, true);
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
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1), TripRescheduleReason.POSTPONED, null);

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", req))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("handover-deadline-past"));
    }

    @Test
    void rescheduleResetsBothSentColumns() {
        announcement.setDeparturePromptSentAt(java.time.OffsetDateTime.now());
        announcement.setArrivalPromptSentAt(java.time.OffsetDateTime.now());
        service.reschedule(announcementId, "uid-traveler", request(newDate, LocalTime.of(23, 0)));
        assertThat(announcement.getDeparturePromptSentAt()).isNull();
        assertThat(announcement.getArrivalPromptSentAt()).isNull();
    }

    // ─── Bateau (lot 3) : la fourchette d'arrivée suit le report ─────────────────

    private void boatWithRange(int minDays, int maxDays) {
        announcement.setTransportMode(TransportMode.BOAT);
        announcement.setArrivalDate(oldDate.plusDays(minDays));
        announcement.setArrivalDateMax(oldDate.plusDays(maxDays));
    }

    private TripRescheduleRequest boatRequest(LocalDate departure, LocalDate arrival) {
        return new TripRescheduleRequest(departure, LocalTime.of(10, 0), arrival, null,
                departure.minusDays(1).atTime(18, 0), TripRescheduleReason.POSTPONED, null);
    }

    @Test
    void reschedule_boatWithRange_shiftsRangeKeepingItsWidth() {
        boatWithRange(30, 40);
        BidEntity handedOver = bid(BidStatus.HANDED_OVER);
        handedOver.setConfirmationCode("123456");
        when(bidRepository.findByAnnouncementIdAndStatusIn(eq(announcementId), anyList()))
                .thenReturn(List.of(handedOver));

        service.reschedule(announcementId, "uid-traveler", boatRequest(newDate, newDate.plusDays(35)));

        assertThat(announcement.getArrivalDate()).isEqualTo(newDate.plusDays(35));
        assertThat(announcement.getArrivalDateMax()).isEqualTo(newDate.plusDays(45));
        // Le code de retrait est recalé sur la NOUVELLE fin de fourchette.
        assertThat(handedOver.getConfirmationCodeExpiry()).isEqualTo(newDate.plusDays(45).atStartOfDay().plusDays(3));
    }

    @Test
    void reschedule_boatRangeBeyond120Days_isShortenedToTheCeiling() {
        boatWithRange(30, 40);

        service.reschedule(announcementId, "uid-traveler", boatRequest(newDate, newDate.plusDays(115)));

        assertThat(announcement.getArrivalDate()).isEqualTo(newDate.plusDays(115));
        assertThat(announcement.getArrivalDateMax()).isEqualTo(newDate.plusDays(120));
    }

    @Test
    void reschedule_boatWithRange_withoutArrivalDate_isRefused() {
        boatWithRange(30, 40);

        assertThatThrownBy(() -> service.reschedule(announcementId, "uid-traveler", boatRequest(newDate, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("arrival-range-invalid"));
        verify(rescheduleRepository, never()).save(any());
    }

    @Test
    void reschedule_withoutRange_keepsArrivalDateMaxNull() {
        announcement.setTransportMode(TransportMode.BOAT);
        service.reschedule(announcementId, "uid-traveler", boatRequest(newDate, newDate.plusDays(30)));

        assertThat(announcement.getArrivalDateMax()).isNull();
        assertThat(TripRescheduleService.shiftedArrivalDateMax(announcement, boatRequest(newDate, null))).isNull();
    }

    @Test
    void reschedule_inProgressBoatPerM3_backOnMarketByVolume() {
        boatWithRange(30, 40);
        announcement.setAvailableKg(null);
        announcement.setPricePerM3(new BigDecimal("600"));
        announcement.setVolumeCapacityM3(new BigDecimal("2"));
        announcement.setAvailableVolumeM3(BigDecimal.ZERO);
        announcement.setStatus(AnnouncementStatus.IN_PROGRESS);

        service.reschedule(announcementId, "uid-traveler", boatRequest(newDate, newDate.plusDays(35)));

        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.FULL);

        announcement.setStatus(AnnouncementStatus.IN_PROGRESS);
        announcement.setAvailableVolumeM3(new BigDecimal("0.5"));
        service.reschedule(announcementId, "uid-traveler", boatRequest(newDate.plusDays(1), newDate.plusDays(36)));

        assertThat(announcement.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
    }
}
