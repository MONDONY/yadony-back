package com.yadony.api.tracking;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.PositionBlurService.BlurredPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripPositionServiceTest {

    private static final Instant NOW_I = Instant.parse("2026-11-01T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(NOW_I, ZoneOffset.UTC);
    private static final String UID = "uid-traveler";
    private static final double LAT = 48.8566123;
    private static final double LON = 2.3522987;

    @Mock AnnouncementRepository announcementRepository;
    @Mock BidRepository bidRepository;
    @Mock UserRepository userRepository;
    @Mock TripPositionSampleRepository sampleRepository;
    @Mock PositionBlurService blurService;
    @Mock AuditService auditService;

    private TripPositionService service;
    private final UUID travelerId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private AnnouncementEntity trip;

    @BeforeEach
    void setUp() {
        service = new TripPositionService(announcementRepository, bidRepository, userRepository,
                sampleRepository, blurService, auditService, Clock.fixed(NOW_I, ZoneOffset.UTC));
        trip = new AnnouncementEntity();
        ReflectionTestUtils.setField(trip, "id", announcementId);
        trip.setTravelerId(travelerId);
        trip.setDepartureCity("Paris");
        trip.setDepartureCountryCode("FR");
        trip.setArrivalCity("Dakar");
        trip.setArrivalCountryCode("SN");
        trip.setStatus(com.yadony.api.matching.AnnouncementStatus.ACTIVE);
        trip.setDepartureAt(NOW.minusMinutes(10));
        lenient().when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(trip));
        lenient().when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId), anyList()))
                .thenReturn(true);
        lenient().when(sampleRepository.save(any(TripPositionSampleEntity.class)))
                .thenAnswer(i -> i.getArgument(0));
        lenient().when(sampleRepository.findFirstByAnnouncementIdOrderByCapturedAtDesc(announcementId))
                .thenReturn(Optional.empty());
        lenient().when(blurService.blur(anyDouble(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(new BlurredPosition("Tours", "FR", 12));
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", travelerId);
        lenient().when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
    }

    @Test
    void recordStoresBlurredSampleInsideWindow() {
        Optional<TripPositionSampleEntity> out =
                service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE);

        assertThat(out).isPresent();
        assertThat(out.get().getCityName()).isEqualTo("Tours");
        assertThat(out.get().getCountryCode()).isEqualTo("FR");
        assertThat(out.get().getProgressPct()).isEqualTo(12);
        assertThat(out.get().getSource()).isEqualTo(SampleSource.OUVERTURE);
        assertThat(out.get().getCapturedAt()).isEqualTo(NOW);
    }

    @Test
    void recordIgnoredOutsideWindow() {
        when(bidRepository.existsByAnnouncementIdAndStatusIn(eq(announcementId), anyList())).thenReturn(false);

        assertThat(service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE)).isEmpty();
        verify(sampleRepository, never()).save(any());
    }

    @Test
    void recordIgnoredMoreThan30MinutesBeforeDeparture() {
        trip.setDepartureAt(NOW.plusMinutes(31));

        assertThat(service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE)).isEmpty();
        trip.setDepartureAt(NOW.plusMinutes(30));
        assertThat(service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE)).isPresent();
    }

    @Test
    void windowFallsBackToDateAndTimeInTripTimezone() {
        trip.setDepartureAt(null);
        trip.setTimezone("Africa/Dakar");
        trip.setDepartureDate(LocalDate.of(2026, 11, 1));
        trip.setDepartureTime(LocalTime.of(12, 20));   // UTC+0 en novembre : 12:20Z, ouvre à 11:50Z

        assertThat(service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE)).isPresent();
        trip.setDepartureTime(LocalTime.of(13, 0));    // ouvre à 12:30Z
        assertThat(service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE)).isEmpty();
    }

    @Test
    void recordRejectsNonOwner() {
        assertThatThrownBy(() -> service.record(announcementId, UUID.randomUUID(), LAT, LON, SampleSource.OUVERTURE))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(sampleRepository, never()).save(any());
    }

    @Test
    void recordThrottledToOneEvery15Minutes() {
        when(sampleRepository.findFirstByAnnouncementIdOrderByCapturedAtDesc(announcementId))
                .thenReturn(Optional.of(sample(NOW.minusMinutes(14))));
        assertThat(service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE)).isEmpty();

        when(sampleRepository.findFirstByAnnouncementIdOrderByCapturedAtDesc(announcementId))
                .thenReturn(Optional.of(sample(NOW.minusMinutes(16))));
        assertThat(service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE)).isPresent();
    }

    @Test
    void recordIgnoredWithoutCoordinatesOrCity() {
        assertThat(service.record(announcementId, travelerId, null, LON, SampleSource.OUVERTURE)).isEmpty();
        when(blurService.blur(anyDouble(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(new BlurredPosition(null, null, null));
        assertThat(service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE)).isEmpty();
        verify(sampleRepository, never()).save(any());
    }

    @Test
    void arrivalSampleIsNotThrottledNorBlockedByArrivedParcels() {

        Optional<TripPositionSampleEntity> out =
                service.record(announcementId, travelerId, LAT, LON, SampleSource.ARRIVEE);

        assertThat(out).isPresent();
        assertThat(out.get().getSource()).isEqualTo(SampleSource.ARRIVEE);
        verify(bidRepository, never()).existsByAnnouncementIdAndStatusIn(any(), anyList());
        verify(sampleRepository, never()).findFirstByAnnouncementIdOrderByCapturedAtDesc(any());
    }

    @Test
    void departWithoutCoordinatesStillSetsDepartedAt() {
        service.depart(announcementId, UID, null, null);

        assertThat(trip.getDepartedAt()).isEqualTo(NOW);
        verify(announcementRepository).save(trip);
        verify(sampleRepository, never()).save(any());
    }

    @Test
    void arrivalSampleIgnoresOutOfRangePosition() {
        for (double[] p : new double[][] {{200, 0}, {-91, 0}, {0, 181}, {0, -181}}) {
            assertThat(service.record(announcementId, travelerId, p[0], p[1], SampleSource.ARRIVEE)).isEmpty();
        }
        verify(sampleRepository, never()).save(any());
        verify(blurService, never()).blur(anyDouble(), anyDouble(), any(), any(), any(), any());
    }

    @Test
    void departIgnoredTooEarly() {
        trip.setDepartureAt(NOW.plusMinutes(31));

        service.depart(announcementId, UID, LAT, LON);

        assertThat(trip.getDepartedAt()).isNull();
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
        verify(sampleRepository, never()).save(any());
    }

    @Test
    void departIgnoredOnCancelledTrip() {
        for (var status : List.of(com.yadony.api.matching.AnnouncementStatus.CANCELLED,
                com.yadony.api.matching.AnnouncementStatus.COMPLETED,
                com.yadony.api.matching.AnnouncementStatus.DRAFT)) {
            trip.setStatus(status);

            service.depart(announcementId, UID, LAT, LON);

            assertThat(trip.getDepartedAt()).as(status.name()).isNull();
        }
        verify(announcementRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void departKeepsFirstDepartedAtAndTakesDepartSample() {
        OffsetDateTime first = NOW.minusHours(1);
        trip.setDepartedAt(first);

        service.depart(announcementId, UID, LAT, LON);

        assertThat(trip.getDepartedAt()).isEqualTo(first);
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
        ArgumentCaptor<TripPositionSampleEntity> c = ArgumentCaptor.forClass(TripPositionSampleEntity.class);
        verify(sampleRepository).save(c.capture());
        assertThat(c.getValue().getSource()).isEqualTo(SampleSource.DEPART);
    }

    @Test
    @SuppressWarnings("unchecked")
    void departWritesAuditLogEntry() {
        service.depart(announcementId, UID, LAT, LON);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("ANNOUNCEMENT"), eq(announcementId), eq("TRIP_DEPARTED"),
                eq(travelerId), payload.capture());
        assertThat(payload.getValue()).containsEntry("hasPosition", true);
        assertThat(payload.getValue().toString()).doesNotContain("48.85").doesNotContain("2.35");
    }

    @Test
    void rawCoordinatesNeverLogged() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        root.setLevel(Level.ALL);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            service.depart(announcementId, UID, LAT, LON);
            service.record(announcementId, travelerId, LAT, LON, SampleSource.OUVERTURE);
            assertThatThrownBy(() -> service.record(announcementId, UUID.randomUUID(), LAT, LON,
                    SampleSource.OUVERTURE)).hasMessageNotContaining("48.85");
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
        assertThat(appender.list).allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain("48.85").doesNotContain("2.352");
        });
    }

    private TripPositionSampleEntity sample(OffsetDateTime at) {
        return new TripPositionSampleEntity(announcementId, at, "Tours", "FR", 10, SampleSource.OUVERTURE);
    }
}
