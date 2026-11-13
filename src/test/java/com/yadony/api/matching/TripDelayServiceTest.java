package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.TripDelayInfo;
import com.yadony.api.matching.dto.TripDelayRequest;
import com.yadony.api.matching.events.TripDelayedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripDelayServiceTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock UserRepository userRepository;
    @Mock TripDelayRepository delayRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    TripDelayService service;

    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final LocalDate departure = LocalDate.now().minusDays(10);
    private AnnouncementEntity announcement;

    @BeforeEach
    void setUp() {
        service = new TripDelayService(announcementRepository, bidRepository, userRepository,
                delayRepository, auditService, eventPublisher);
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        lenient().when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", announcementId);
        announcement.setTravelerId(travelerId);
        announcement.setTransportMode(TransportMode.BOAT);
        announcement.setStatus(AnnouncementStatus.IN_PROGRESS);
        announcement.setDepartureDate(departure);
        announcement.setArrivalDate(departure.plusDays(20));
        announcement.setArrivalDateMax(departure.plusDays(30));
        lenient().when(announcementRepository.findByIdForUpdate(announcementId)).thenReturn(Optional.of(announcement));
        lenient().when(delayRepository.save(any())).thenAnswer(inv -> {
            TripDelayEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(e, "createdAt", LocalDateTime.of(2026, 11, 13, 9, 30));
            return e;
        });
    }

    private TripDelayRequest delay(LocalDate min, LocalDate max) {
        return new TripDelayRequest(TripDelayReason.PORT_CONGESTION, min, max, "  Quai saturé à Dakar  ");
    }

    /** Retard au motif neutre, valable sur tous les modes (PORT_CONGESTION est propre au bateau). */
    private TripDelayRequest neutralDelay(LocalDate min, LocalDate max) {
        return new TripDelayRequest(TripDelayReason.TRANSPORT_DISRUPTION, min, max, "  Quai saturé à Dakar  ");
    }

    private TripDelayRequest delay() {
        return delay(departure.plusDays(40), departure.plusDays(50));
    }

    private BidEntity bid(BidStatus status, String code) {
        BidEntity bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setAnnouncementId(announcementId);
        bid.setStatus(status);
        bid.setConfirmationCode(code);
        bid.setConfirmationCodeExpiry(departure.plusDays(31).atStartOfDay());
        return bid;
    }

    private static void assertProblem(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getStatus()).isEqualTo(status);
        });
    }

    @Test
    void delay_updatesArrivalRangeAndCodeExpiry() {
        BidEntity handedOver = bid(BidStatus.HANDED_OVER, "123456");
        BidEntity inTransit = bid(BidStatus.IN_TRANSIT, "654321");
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId))
                .thenReturn(List.of(handedOver, inTransit));

        TripDelayInfo info = service.announce(announcementId, "uid-traveler", delay());

        assertThat(announcement.getArrivalDate()).isEqualTo(departure.plusDays(40));
        assertThat(announcement.getArrivalDateMax()).isEqualTo(departure.plusDays(50));
        LocalDateTime expected = ArrivalRules.pickupCodeExpiry(announcement);
        assertThat(expected).isEqualTo(departure.plusDays(50).atStartOfDay().plusDays(3));
        assertThat(handedOver.getConfirmationCodeExpiry()).isEqualTo(expected);
        assertThat(inTransit.getConfirmationCodeExpiry()).isEqualTo(expected);
        verify(bidRepository).save(handedOver);
        verify(bidRepository).save(inTransit);
        assertThat(info.reason()).isEqualTo("PORT_CONGESTION");
        assertThat(info.arrivalDate()).isEqualTo(departure.plusDays(40));
        assertThat(info.arrivalDateMax()).isEqualTo(departure.plusDays(50));
        assertThat(info.message()).isEqualTo("Quai saturé à Dakar");
        assertThat(info.announcedAt()).isNotNull();
    }

    @Test
    void delay_keepsThePreviousRangeInTheRow() {
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());

        service.announce(announcementId, "uid-traveler", delay());

        ArgumentCaptor<TripDelayEntity> row = ArgumentCaptor.forClass(TripDelayEntity.class);
        verify(delayRepository).save(row.capture());
        assertThat(row.getValue().getPreviousArrivalDate()).isEqualTo(departure.plusDays(20));
        assertThat(row.getValue().getPreviousArrivalDateMax()).isEqualTo(departure.plusDays(30));
        assertThat(row.getValue().getNewArrivalDate()).isEqualTo(departure.plusDays(40));
        assertThat(row.getValue().getNewArrivalDateMax()).isEqualTo(departure.plusDays(50));
        assertThat(row.getValue().getCreatedBy()).isEqualTo(travelerId);
        assertThat(row.getValue().getAnnouncementId()).isEqualTo(announcementId);
    }

    @Test
    void delay_resetsTheArrivalPrompt() {
        announcement.setArrivalPromptSentAt(java.time.OffsetDateTime.now().minusHours(2));
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());

        service.announce(announcementId, "uid-traveler", delay());

        assertThat(announcement.getArrivalPromptSentAt()).isNull();
        verify(announcementRepository).save(announcement);
    }

    @Test
    void delay_notifiesEveryParcelReturnedByTheLockQuery() {
        // Le filtre des statuts (livrés, annulés, en attente exclus) est celui de la requête
        // findHandedOverByAnnouncementIdForUpdate : il est prouvé sur Postgres dans TripDelayControllerIT
        // (colis livré, annulé et en attente intacts et absents de l'événement). Ici le service notifie
        // tout ce que la requête lui rend, ni plus ni moins.
        BidEntity b1 = bid(BidStatus.HANDED_OVER, "111111");
        BidEntity b2 = bid(BidStatus.IN_TRANSIT, "222222");
        BidEntity b3 = bid(BidStatus.ARRIVED, "333333");
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId))
                .thenReturn(List.of(b1, b2, b3));

        service.announce(announcementId, "uid-traveler", delay());

        ArgumentCaptor<TripDelayedEvent> event = ArgumentCaptor.forClass(TripDelayedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().bidIds()).containsExactlyInAnyOrder(b1.getId(), b2.getId(), b3.getId());
        assertThat(event.getValue().targets()).extracting(TripDelayedEvent.Target::senderId)
                .containsExactlyInAnyOrder(b1.getSenderId(), b2.getSenderId(), b3.getSenderId());
        assertThat(event.getValue().announcementId()).isEqualTo(announcementId);
        assertThat(event.getValue().travelerId()).isEqualTo(travelerId);
        assertThat(event.getValue().reason()).isEqualTo("PORT_CONGESTION");
        assertThat(event.getValue().arrivalDate()).isEqualTo(departure.plusDays(40));
        assertThat(event.getValue().arrivalDateMax()).isEqualTo(departure.plusDays(50));
        assertThat(event.getValue().message()).isEqualTo("Quai saturé à Dakar");
        verify(bidRepository, never()).findByAnnouncementIdAndStatusIn(any(), any());
    }

    @Test
    void delay_onlyLocksTheEnRouteParcelsOrderedByCreation() {
        // Même ordre que le report et l'annulation : le trajet, puis les colis par date de création.
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());

        service.announce(announcementId, "uid-traveler", delay());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(announcementRepository, bidRepository);
        order.verify(announcementRepository).findByIdForUpdate(announcementId);
        order.verify(bidRepository).findHandedOverByAnnouncementIdForUpdate(announcementId);
    }

    @Test
    void delay_parcelWithoutCode_isNotifiedButItsExpiryIsLeftAlone() {
        BidEntity noCode = bid(BidStatus.HANDED_OVER, null);
        LocalDateTime before = noCode.getConfirmationCodeExpiry();
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of(noCode));

        service.announce(announcementId, "uid-traveler", delay());

        assertThat(noCode.getConfirmationCodeExpiry()).isEqualTo(before);
        verify(bidRepository, never()).save(any());
        ArgumentCaptor<TripDelayedEvent> event = ArgumentCaptor.forClass(TripDelayedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().bidIds()).containsExactly(noCode.getId());
    }

    @Test
    void delay_beforeDeparture_409() {
        announcement.setStatus(AnnouncementStatus.ACTIVE);

        assertProblem(() -> service.announce(announcementId, "uid-traveler", delay()),
                HttpStatus.CONFLICT, "trip-delay-not-allowed");
        verify(delayRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void delay_onCompletedOrFullTrip_409() {
        for (AnnouncementStatus status : List.of(AnnouncementStatus.FULL, AnnouncementStatus.COMPLETED,
                AnnouncementStatus.CANCELLED)) {
            announcement.setStatus(status);
            assertProblem(() -> service.announce(announcementId, "uid-traveler", delay()),
                    HttpStatus.CONFLICT, "trip-delay-not-allowed");
        }
    }

    @Test
    void delay_maxBeforeMin_422() {
        assertProblem(() -> service.announce(announcementId, "uid-traveler",
                        delay(departure.plusDays(40), departure.plusDays(30))),
                HttpStatus.UNPROCESSABLE_ENTITY, "arrival-range-invalid");
        verify(delayRepository, never()).save(any());
    }

    @Test
    void delay_minBeforeDeparture_422_withItsOwnMessage() {
        assertThatThrownBy(() -> service.announce(announcementId, "uid-traveler",
                        delay(departure.minusDays(1), departure.plusDays(30))))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("arrival-range-invalid");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    // Détail propre à « début avant le départ », pas celui de « fin avant début ».
                    assertThat(e.getMessage()).contains("précéder la date de départ");
                });
        verify(delayRepository, never()).save(any());
    }

    @Test
    void delay_endInThePast_422_andNothingIsWritten() {
        // Fourchette entièrement passée (départ il y a 10 jours) : une avance, pas un retard ; elle
        // ferait expirer les codes de retrait des colis en route.
        BidEntity handedOver = bid(BidStatus.HANDED_OVER, "123456");
        LocalDateTime before = handedOver.getConfirmationCodeExpiry();

        assertThatThrownBy(() -> service.announce(announcementId, "uid-traveler",
                        delay(departure.plusDays(1), departure.plusDays(2))))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("arrival-range-invalid");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).contains("antérieure à aujourd'hui");
                });
        verify(delayRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(handedOver.getConfirmationCodeExpiry()).isEqualTo(before);
    }

    @Test
    void delay_endingToday_isAccepted_andTheCodeStillGetsAtLeast24Hours() {
        BidEntity handedOver = bid(BidStatus.HANDED_OVER, "123456");
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of(handedOver));
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);

        service.announce(announcementId, "uid-traveler", delay(today, today));

        assertThat(handedOver.getConfirmationCodeExpiry())
                .isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(23));
    }

    @Test
    void delay_beyond120Days_422() {
        assertProblem(() -> service.announce(announcementId, "uid-traveler",
                        delay(departure.plusDays(100), departure.plusDays(121))),
                HttpStatus.UNPROCESSABLE_ENTITY, "arrival-too-far");
    }

    @Test
    void delay_exactly120Days_isAccepted() {
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());

        service.announce(announcementId, "uid-traveler", delay(departure.plusDays(100), departure.plusDays(120)));

        assertThat(announcement.getArrivalDateMax()).isEqualTo(departure.plusDays(120));
    }

    @Test
    void delay_byOtherUser_403() {
        UserEntity other = new UserEntity();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        when(userRepository.findByFirebaseUid("uid-other")).thenReturn(Optional.of(other));

        assertProblem(() -> service.announce(announcementId, "uid-other", delay()),
                HttpStatus.FORBIDDEN, "forbidden");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void delay_unknownUserOrTrip_404() {
        assertProblem(() -> service.announce(announcementId, "uid-nobody", delay()),
                HttpStatus.NOT_FOUND, "user-not-found");
        UUID missing = UUID.randomUUID();
        when(announcementRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertProblem(() -> service.announce(missing, "uid-traveler", delay()),
                HttpStatus.NOT_FOUND, "announcement-not-found");
    }

    @Test
    void delay_writesAuditAndRow() {
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId))
                .thenReturn(List.of(bid(BidStatus.HANDED_OVER, "123456")));

        service.announce(announcementId, "uid-traveler", delay());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("ANNOUNCEMENT"), eq(announcementId), eq("TRIP_DELAY_ANNOUNCED"),
                eq(travelerId), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("reason", "PORT_CONGESTION")
                .containsEntry("previousArrivalDate", departure.plusDays(20).toString())
                .containsEntry("previousArrivalDateMax", departure.plusDays(30).toString())
                .containsEntry("newArrivalDate", departure.plusDays(40).toString())
                .containsEntry("newArrivalDateMax", departure.plusDays(50).toString())
                .containsEntry("parcelsNotified", "1")
                .containsKey("delayId");
        verify(delayRepository).save(any(TripDelayEntity.class));
    }

    /** Trajet routier parti il y a deux jours, arrivée prévue demain : les dates restent dans le futur proche. */
    private LocalDate carTrip() {
        LocalDate carDeparture = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2);
        announcement.setTransportMode(TransportMode.CAR);
        announcement.setDepartureDate(carDeparture);
        announcement.setArrivalDate(carDeparture.plusDays(1));
        announcement.setArrivalDateMax(null);
        return carDeparture;
    }

    @Test
    void delay_onNonBoatTrip_writesOnlyTheEarliestArrivalButKeepsTheRangeInTheRow() {
        LocalDate carDeparture = carTrip();
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());

        TripDelayInfo info = service.announce(announcementId, "uid-traveler",
                neutralDelay(carDeparture.plusDays(3), carDeparture.plusDays(6)));

        assertThat(announcement.getArrivalDate()).isEqualTo(carDeparture.plusDays(3));
        assertThat(announcement.getArrivalDateMax()).isNull();
        assertThat(info.arrivalDateMax()).isEqualTo(carDeparture.plusDays(6));
        ArgumentCaptor<TripDelayEntity> row = ArgumentCaptor.forClass(TripDelayEntity.class);
        verify(delayRepository).save(row.capture());
        assertThat(row.getValue().getNewArrivalDateMax()).isEqualTo(carDeparture.plusDays(6));
        assertThat(row.getValue().getPreviousArrivalDateMax()).isNull();
    }

    @Test
    void delay_onNonBoatTrip_codeExpiryFollowsTheEndOfTheRange() {
        // L'annonce ne garde que le début hors bateau : sans calcul sur la fin demandée, le code expirerait
        // à début + 72 h, avant la fin de la fourchette annoncée (FLUTTER-4E).
        LocalDate carDeparture = carTrip();
        BidEntity handedOver = bid(BidStatus.IN_TRANSIT, "123456");
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of(handedOver));

        service.announce(announcementId, "uid-traveler", neutralDelay(carDeparture.plusDays(3), carDeparture.plusDays(12)));

        assertThat(handedOver.getConfirmationCodeExpiry())
                .isEqualTo(carDeparture.plusDays(12).atStartOfDay().plusDays(3));
    }

    @Test
    void delay_onNonBoatTrip_endStaysWithinTheModeLimit() {
        LocalDate carDeparture = carTrip();
        // Route : 14 jours au plus après le départ, jour inclus ; la fin compte, pas seulement le début.
        assertProblem(() -> service.announce(announcementId, "uid-traveler",
                        neutralDelay(carDeparture.plusDays(3), carDeparture.plusDays(15))),
                HttpStatus.UNPROCESSABLE_ENTITY, "arrival-too-far");

        announcement.setTransportMode(TransportMode.PLANE);
        assertProblem(() -> service.announce(announcementId, "uid-traveler",
                        neutralDelay(carDeparture.plusDays(1), carDeparture.plusDays(4))),
                HttpStatus.UNPROCESSABLE_ENTITY, "arrival-too-far");
        verify(delayRepository, never()).save(any());
    }

    @Test
    void delay_onNonBoatTrip_earliestArrivalStaysWithinTheModeLimit() {
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setArrivalDateMax(null);

        assertProblem(() -> service.announce(announcementId, "uid-traveler",
                        neutralDelay(departure.plusDays(40), departure.plusDays(50))),
                HttpStatus.UNPROCESSABLE_ENTITY, "arrival-too-far");
    }

    @Test
    void delay_portCongestion_isBoatOnly() {
        LocalDate carDeparture = carTrip();
        for (TransportMode mode : List.of(TransportMode.CAR, TransportMode.BUS, TransportMode.PLANE,
                TransportMode.TRAIN, TransportMode.OTHER)) {
            announcement.setTransportMode(mode);
            assertProblem(() -> service.announce(announcementId, "uid-traveler",
                            delay(carDeparture.plusDays(1), carDeparture.plusDays(2))),
                    HttpStatus.UNPROCESSABLE_ENTITY, "trip-delay-reason-invalid");
        }
        verify(delayRepository, never()).save(any());
    }

    @Test
    void delay_neutralReason_isAcceptedOnEveryMode() {
        LocalDate carDeparture = carTrip();
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());
        TripDelayRequest neutral = new TripDelayRequest(TripDelayReason.TRANSPORT_DISRUPTION,
                carDeparture.plusDays(2), carDeparture.plusDays(3), null);

        assertThat(service.announce(announcementId, "uid-traveler", neutral).reason())
                .isEqualTo("TRANSPORT_DISRUPTION");
        announcement.setTransportMode(TransportMode.BOAT);
        announcement.setArrivalDate(carDeparture.plusDays(1));
        announcement.setArrivalDateMax(carDeparture.plusDays(2));
        assertThat(service.announce(announcementId, "uid-traveler", neutral).reason())
                .isEqualTo("TRANSPORT_DISRUPTION");
    }

    private TripDelayEntity existingDelay(TripDelayReason reason, LocalDate min, LocalDate max, String message,
                                          LocalDateTime createdAt) {
        TripDelayEntity e = new TripDelayEntity();
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(e, "createdAt", createdAt);
        e.setAnnouncementId(announcementId);
        e.setReason(reason);
        e.setNewArrivalDate(min);
        e.setNewArrivalDateMax(max);
        e.setMessage(message);
        return e;
    }

    private void recentDelays(List<TripDelayEntity> newestFirst) {
        when(delayRepository.findByAnnouncementIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq(announcementId), any())).thenReturn(newestFirst);
    }

    @Test
    void delay_identicalToTheLastOneOfTheLastMinutes_isAReplay_andWritesNothing() {
        TripDelayEntity last = existingDelay(TripDelayReason.PORT_CONGESTION, departure.plusDays(40),
                departure.plusDays(50), "Quai saturé à Dakar", LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(2));
        recentDelays(List.of(last));

        TripDelayInfo info = service.announce(announcementId, "uid-traveler", delay());

        assertThat(info.id()).isEqualTo(last.getId());
        assertThat(info.arrivalDateMax()).isEqualTo(departure.plusDays(50));
        verify(delayRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(bidRepository, never()).findHandedOverByAnnouncementIdForUpdate(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void delay_sameRangeButAnotherReasonOrMessage_isNotAReplay() {
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());
        LocalDateTime recent = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(2);
        recentDelays(List.of(existingDelay(TripDelayReason.WEATHER, departure.plusDays(40),
                departure.plusDays(50), "Quai saturé à Dakar", recent)));
        service.announce(announcementId, "uid-traveler", delay());

        recentDelays(List.of(existingDelay(TripDelayReason.PORT_CONGESTION, departure.plusDays(40),
                departure.plusDays(50), "Autre message", recent)));
        service.announce(announcementId, "uid-traveler", delay());

        verify(delayRepository, org.mockito.Mockito.times(2)).save(any(TripDelayEntity.class));
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(any(TripDelayedEvent.class));
    }

    @Test
    void delay_identicalButOlderThanTheReplayWindow_isANewDelay() {
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());
        recentDelays(List.of(existingDelay(TripDelayReason.PORT_CONGESTION, departure.plusDays(40),
                departure.plusDays(50), "Quai saturé à Dakar",
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(TripDelayService.REPLAY_WINDOW_MINUTES + 1))));

        service.announce(announcementId, "uid-traveler", delay());

        verify(delayRepository).save(any(TripDelayEntity.class));
        verify(eventPublisher).publishEvent(any(TripDelayedEvent.class));
    }

    @Test
    void delay_sixthInTwentyFourHours_409TooFrequent_andNothingIsWritten() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        java.util.ArrayList<TripDelayEntity> five = new java.util.ArrayList<>();
        for (int i = 0; i < TripDelayService.MAX_DELAYS_PER_DAY; i++) {
            five.add(existingDelay(TripDelayReason.WEATHER, departure.plusDays(30 + i), departure.plusDays(40 + i),
                    null, now.minusHours(1 + i)));
        }
        recentDelays(five);

        assertThatThrownBy(() -> service.announce(announcementId, "uid-traveler", delay()))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("trip-delay-too-frequent");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(delayRepository, never()).save(any());
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void delay_fifthInTwentyFourHours_isStillAccepted() {
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        java.util.ArrayList<TripDelayEntity> four = new java.util.ArrayList<>();
        for (int i = 0; i < TripDelayService.MAX_DELAYS_PER_DAY - 1; i++) {
            four.add(existingDelay(TripDelayReason.WEATHER, departure.plusDays(30 + i), departure.plusDays(40 + i),
                    null, now.minusHours(1 + i)));
        }
        recentDelays(four);

        service.announce(announcementId, "uid-traveler", delay());

        verify(delayRepository).save(any(TripDelayEntity.class));
    }

    @Test
    void delay_blankMessageIsStoredAsNull() {
        when(bidRepository.findHandedOverByAnnouncementIdForUpdate(announcementId)).thenReturn(List.of());

        TripDelayInfo info = service.announce(announcementId, "uid-traveler",
                new TripDelayRequest(TripDelayReason.WEATHER, departure.plusDays(40), departure.plusDays(50), "   "));

        assertThat(info.message()).isNull();
        TripDelayInfo none = service.announce(announcementId, "uid-traveler",
                new TripDelayRequest(TripDelayReason.OTHER, departure.plusDays(40), departure.plusDays(50), null));
        assertThat(none.message()).isNull();
    }

    @Test
    void tripDelayInfo_from_nullIsNull_andMissingCreatedAtGivesNoInstant() {
        assertThat(TripDelayInfo.from(null)).isNull();
        TripDelayEntity e = new TripDelayEntity();
        e.setReason(TripDelayReason.OTHER);
        e.setNewArrivalDate(LocalDate.of(2026, 12, 1));
        e.setNewArrivalDateMax(LocalDate.of(2026, 12, 5));
        assertThat(TripDelayInfo.from(e).announcedAt()).isNull();
    }

    @Test
    void prefetch_servesTheLoadedTableOrFallsBackToTheSingleRead() {
        BidResponsePrefetch prefetch = new BidResponsePrefetch(Map.of(), java.util.Set.of(), Map.of(), Map.of(), Map.of());
        TripDelayInfo fromSingleRead = new TripDelayInfo(UUID.randomUUID(), "WEATHER",
                LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), null, null);

        // Table pas chargée : lecture unitaire.
        assertThat(prefetch.latestDelay(announcementId, () -> fromSingleRead)).isSameAs(fromSingleRead);

        // Table chargée : on la lit, jamais la lecture unitaire (un trajet absent = aucun retard).
        TripDelayInfo loaded = new TripDelayInfo(UUID.randomUUID(), "OTHER",
                LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 6), "m", null);
        prefetch.setLatestDelays(Map.of(announcementId, loaded));
        assertThat(prefetch.latestDelay(announcementId, () -> { throw new AssertionError("pas de requête"); }))
                .isSameAs(loaded);
        assertThat(prefetch.latestDelay(UUID.randomUUID(), () -> { throw new AssertionError("pas de requête"); }))
                .isNull();
    }
}
