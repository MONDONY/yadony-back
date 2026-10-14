package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripSeriesRequest;
import com.yadony.api.matching.dto.TripSeriesResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripSeriesServiceTest {

    /** 15 octobre 2026, 10 h UTC. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate SOURCE_DATE = LocalDate.of(2026, 11, 20);
    private static final UUID SERIES = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();

    private final AnnouncementService announcements = mock(AnnouncementService.class);
    private final AnnouncementRepository repository = mock(AnnouncementRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final TripSeriesService service = new TripSeriesService(announcements, repository, audit, CLOCK);

    private static AnnouncementRequest base(LocalDateTime deadline, LocalDate arrivalDate) {
        return new AnnouncementRequest("Paris", "Dakar", SOURCE_DATE, LocalTime.of(10, 0), LocalTime.of(6, 0),
                new AddressDto("Orly", 48.72, 2.37), new AddressDto("Dakar", 14.69, -17.44),
                BigDecimal.valueOf(23), BigDecimal.valueOf(8), TransportMode.PLANE, "note",
                List.of("CLOTHES"), List.of(), null, CapacityUnit.SUITCASE_23KG, PricingMode.KG,
                "FR", "SN", deadline, Boolean.FALSE, true, "EUR", arrivalDate, 0);
    }

    private static TripSeriesStart start(AnnouncementRequest base) {
        return new TripSeriesStart(SERIES, SOURCE_DATE, ZoneId.of("Europe/Paris"), base);
    }

    private static AnnouncementResponse response(UUID travelerId) {
        AnnouncementResponse r = mock(AnnouncementResponse.class);
        when(r.travelerId()).thenReturn(travelerId);
        return r;
    }

    @Test
    void shiftTo_movesDateDeadlineAndArrivalByTheSameNumberOfDays() {
        AnnouncementRequest shifted = service.shiftTo(
                start(base(SOURCE_DATE.minusDays(1).atTime(18, 0), SOURCE_DATE.plusDays(1))),
                SOURCE_DATE.plusDays(7), true);

        assertThat(shifted.departureDate()).isEqualTo(SOURCE_DATE.plusDays(7));
        assertThat(shifted.handoverDeadline()).isEqualTo(SOURCE_DATE.plusDays(6).atTime(18, 0));
        assertThat(shifted.arrivalDate()).isEqualTo(SOURCE_DATE.plusDays(8));
        assertThat(shifted.isDraft()).isTrue();
        // Le reste du trajet est recopié tel quel.
        assertThat(shifted.pickupAddress().label()).isEqualTo("Orly");
        assertThat(shifted.availableKg()).isEqualByComparingTo("23");
        assertThat(shifted.isNegotiable()).isTrue();
        assertThat(shifted.acceptedContentTypes()).containsExactly("CLOTHES");
        assertThat(shifted.stopsCount()).isZero();
    }

    @Test
    void shiftTo_earlierDate_withDeadlineAlreadyPast_fallsBackToDepartureTime() {
        // Copie vers le 16/10 : la date limite décalée (13/10) est déjà passée le 15/10.
        LocalDate tomorrow = LocalDate.of(2026, 10, 16);
        LocalDateTime deadline = SOURCE_DATE.minusDays(3).atTime(8, 0);

        AnnouncementRequest shifted = service.shiftTo(start(base(deadline, null)), tomorrow, false);

        assertThat(shifted.handoverDeadline()).isEqualTo(tomorrow.atTime(10, 0));
        assertThat(shifted.arrivalDate()).isNull();
        assertThat(shifted.isDraft()).isFalse();
    }

    @Test
    void createSeries_sourceDateAmongDates_isRejected() {
        when(announcements.startSeries("uid", SOURCE)).thenReturn(start(base(null, null)));

        assertThatThrownBy(() -> service.createSeries("uid", SOURCE,
                new TripSeriesRequest(List.of(SOURCE_DATE), false)))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("series-invalid-dates");
        verify(announcements, never()).createSeriesCopy(any(), any(), any());
    }

    @Test
    void createSeries_refusalOnOneDate_doesNotStopTheOthers_andIsAuditedOnce() {
        UUID traveler = UUID.randomUUID();
        when(announcements.startSeries("uid", SOURCE)).thenReturn(start(base(null, null)));
        LocalDate d1 = SOURCE_DATE.plusDays(7);
        LocalDate d2 = SOURCE_DATE.plusDays(14);
        AnnouncementResponse ok = response(traveler);
        when(announcements.createSeriesCopy(eq("uid"), argThat(r -> r != null && d1.equals(r.departureDate())), eq(SERIES)))
                .thenReturn(ok);
        when(announcements.createSeriesCopy(eq("uid"), argThat(r -> r != null && d2.equals(r.departureDate())), eq(SERIES)))
                .thenThrow(new YadonyBusinessException(HttpStatus.FORBIDDEN, "draft-limit-reached", "t", "Limite"));

        TripSeriesResponse res = service.createSeries("uid", SOURCE,
                new TripSeriesRequest(List.of(d2, d1, d1), true));

        assertThat(res.seriesId()).isEqualTo(SERIES);
        assertThat(res.created()).containsExactly(ok);
        assertThat(res.failed()).singleElement().satisfies(f -> {
            assertThat(f.date()).isEqualTo(d2);
            assertThat(f.code()).isEqualTo("draft-limit-reached");
        });
        verify(audit).log(eq("ANNOUNCEMENT"), eq(SERIES), eq("TRIP_SERIES_CREATED"), eq(traveler), anyMap());
    }

    @Test
    void createSeries_dateAlreadyInTheSeries_isSkipped() {
        when(announcements.startSeries("uid", SOURCE)).thenReturn(start(base(null, null)));
        LocalDate taken = SOURCE_DATE.plusDays(7);
        when(repository.existsBySeriesIdAndDepartureDateAndStatusNotIn(eq(SERIES), eq(taken), any()))
                .thenReturn(true);

        TripSeriesResponse res = service.createSeries("uid", SOURCE, new TripSeriesRequest(List.of(taken), false));

        assertThat(res.created()).isEmpty();
        assertThat(res.failed()).singleElement()
                .extracting(TripSeriesResponse.Failure::code).isEqualTo("series-date-taken");
        verify(announcements, never()).createSeriesCopy(any(), any(), any());
        verify(audit, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void createSeries_unexpectedError_isReportedWithoutDetail() {
        when(announcements.startSeries("uid", SOURCE)).thenReturn(start(base(null, null)));
        when(announcements.createSeriesCopy(any(), any(), any())).thenThrow(new IllegalStateException("db"));

        TripSeriesResponse res = service.createSeries("uid", SOURCE,
                new TripSeriesRequest(List.of(SOURCE_DATE.plusDays(1)), false));

        assertThat(res.failed()).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("series-copy-failed");
            assertThat(f.detail()).isNull();
        });
    }
}
