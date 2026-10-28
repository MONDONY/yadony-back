package com.yadony.api.tracking;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.TripArrivalInstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripPromptSchedulerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T12:00:00Z");

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock ApplicationEventPublisher publisher;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private AnnouncementEntity trip;
    private OffsetDateTime clockNow = NOW;

    private TripPromptScheduler scheduler() {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return clockNow.toInstant(); }
        };
        return new TripPromptScheduler(announcementRepository, bidRepository,
                new TripArrivalInstant(announcementRepository), publisher, clock);
    }

    @BeforeEach
    void setUp() {
        trip = new AnnouncementEntity();
        ReflectionTestUtils.setField(trip, "id", announcementId);
        trip.setTravelerId(travelerId);
        trip.setStatus(AnnouncementStatus.ACTIVE);
        trip.setTimezone("Europe/Paris");
        trip.setDepartureDate(LocalDate.of(2026, 11, 1));
        trip.setDepartureTime(LocalTime.of(13, 0));
        trip.setDepartureAt(NOW.plusMinutes(30));
        lenient().when(announcementRepository.findDeparturePromptCandidates(any(), any())).thenReturn(List.of(trip));
        lenient().when(announcementRepository.findArrivalPromptCandidates(any(), any())).thenReturn(List.of(trip));
        lenient().when(announcementRepository.claimDeparturePrompt(eq(announcementId), any())).thenReturn(1);
        lenient().when(announcementRepository.claimArrivalPrompt(eq(announcementId), any())).thenReturn(1);
        lenient().when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(true);
    }

    private void arrivesAt(String zoneOfCity, LocalDate day, LocalTime time) {
        trip.setArrivalDate(day);
        trip.setArrivalTime(time);
        trip.setArrivalCity("Abidjan");
        trip.setArrivalCountryCode("CI");
        lenient().when(announcementRepository.findTimezoneByCityName("Abidjan", "CI"))
                .thenReturn(Optional.of(zoneOfCity));
    }

    private TripPromptDueEvent captured() {
        ArgumentCaptor<TripPromptDueEvent> c = ArgumentCaptor.forClass(TripPromptDueEvent.class);
        verify(publisher).publishEvent(c.capture());
        return c.getValue();
    }

    @Test
    void departurePromptSentOnceAtMinus30() {
        var s = scheduler();
        s.sendDeparturePrompts();
        TripPromptDueEvent e = captured();
        assertThat(e.announcementId()).isEqualTo(announcementId);
        assertThat(e.travelerId()).isEqualTo(travelerId);
        assertThat(e.kind()).isEqualTo(TripPromptDueEvent.Kind.DEPARTURE);

        // Deuxième passage : la ligne est déjà réclamée, rien ne repart.
        when(announcementRepository.claimDeparturePrompt(eq(announcementId), any())).thenReturn(0);
        s.sendDeparturePrompts();
        verify(publisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void noDeparturePromptBeforeMinus30() {
        trip.setDepartureAt(NOW.plusMinutes(31));
        scheduler().sendDeparturePrompts();
        verify(announcementRepository, never()).claimDeparturePrompt(any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void noPromptWithoutScannedParcel() {
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(false);
        scheduler().sendDeparturePrompts();
        verify(announcementRepository, never()).claimDeparturePrompt(any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void promptNotSentAfterUpperBound() {
        trip.setDepartureAt(NOW.minusMinutes(61));
        scheduler().sendDeparturePrompts();
        verify(announcementRepository, never()).claimDeparturePrompt(any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void noDeparturePromptForCancelledTrip() {
        trip.setStatus(AnnouncementStatus.CANCELLED);
        scheduler().sendDeparturePrompts();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void claimPreventsDoubleSend() {
        when(announcementRepository.claimDeparturePrompt(eq(announcementId), any())).thenReturn(0);
        scheduler().sendDeparturePrompts();
        verify(announcementRepository).claimDeparturePrompt(eq(announcementId), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void arrivalPromptUsesArrivalCityTimezone() {
        // Le trajet est lu à Tokyo (+9), la ville d'arrivée est en UTC : l'arrivée est à 06:30Z.
        trip.setTimezone("Asia/Tokyo");
        arrivesAt("Africa/Abidjan", LocalDate.of(2026, 11, 1), LocalTime.of(6, 30));
        trip.setDepartureAt(OffsetDateTime.parse("2026-11-01T01:00:00Z"));
        clockNow = OffsetDateTime.parse("2026-11-01T06:45:00Z");
        scheduler().sendArrivalPrompts();
        verify(publisher, never()).publishEvent(any(Object.class));

        clockNow = OffsetDateTime.parse("2026-11-01T07:01:00Z");
        scheduler().sendArrivalPrompts();
        assertThat(captured().kind()).isEqualTo(TripPromptDueEvent.Kind.ARRIVAL);
    }

    @Test
    void noArrivalPromptAfterUpperBound() {
        arrivesAt("Africa/Abidjan", LocalDate.of(2026, 10, 30), LocalTime.of(11, 0));
        scheduler().sendArrivalPrompts();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void noArrivalPromptWithoutArrivalTime() {
        trip.setArrivalDate(LocalDate.of(2026, 10, 31));
        trip.setArrivalTime(null);
        scheduler().sendArrivalPrompts();
        verify(announcementRepository, never()).claimArrivalPrompt(any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void noArrivalPromptIfAlreadyMarkedArrived() {
        arrivesAt("Africa/Abidjan", LocalDate.of(2026, 11, 1), LocalTime.of(6, 30));
        trip.setDepartureAt(OffsetDateTime.parse("2026-11-01T01:00:00Z"));
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(false);
        scheduler().sendArrivalPrompts();
        verify(announcementRepository, never()).claimArrivalPrompt(any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void arrivalClaimPreventsDoubleSend() {
        arrivesAt("Africa/Abidjan", LocalDate.of(2026, 11, 1), LocalTime.of(6, 30));
        trip.setDepartureAt(OffsetDateTime.parse("2026-11-01T01:00:00Z"));
        when(announcementRepository.claimArrivalPrompt(eq(announcementId), any())).thenReturn(0);
        scheduler().sendArrivalPrompts();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void noDeparturePromptAfterDepartPressed() {
        trip.setDepartedAt(NOW.minusMinutes(5));
        scheduler().sendDeparturePrompts();
        verify(announcementRepository, never()).claimDeparturePrompt(any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void arrivalPromptStillSentAfterDepartPressed() {
        trip.setDepartedAt(NOW.minusHours(8));
        arrivesAt("Africa/Abidjan", LocalDate.of(2026, 11, 1), LocalTime.of(6, 30));
        clockNow = OffsetDateTime.parse("2026-11-01T07:01:00Z");
        trip.setDepartureAt(OffsetDateTime.parse("2026-11-01T01:00:00Z"));
        scheduler().sendArrivalPrompts();
        assertThat(captured().kind()).isEqualTo(TripPromptDueEvent.Kind.ARRIVAL);
    }

    @Test
    void noArrivalPromptWhenArrivalInstantBeforeDeparture() {
        // Départ 13:00Z, arrivée saisie 06:30 le même jour (ancien trajet sans date d'arrivée).
        arrivesAt("Africa/Abidjan", LocalDate.of(2026, 11, 1), LocalTime.of(6, 30));
        clockNow = OffsetDateTime.parse("2026-11-01T07:01:00Z");
        scheduler().sendArrivalPrompts();
        verify(announcementRepository, never()).claimArrivalPrompt(any(), any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
