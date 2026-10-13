package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.yadony.api.flights.FlightTestData.alert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlightAlertMatchesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    static TravelpayoutsPrice offer(String airline, String amount, String departureAt) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", departureAt, null, new BigDecimal(amount),
                airline, "1", 0, null, 400, "Partner", "/search/X?t=" + airline + amount + departureAt);
    }

    @Test
    void inWindow_queriesEachMonth_filtersExactPeriod_dedupsAndSorts() {
        FlightPriceAlertEntity a = alert(UUID.randomUUID(), "400");
        a.setDepartureFrom(LocalDate.of(2026, 11, 25));
        a.setDepartureTo(LocalDate.of(2026, 12, 5));
        FlightPriceService prices = mock(FlightPriceService.class);
        TravelpayoutsPrice shared = offer("AF", "300", "2026-11-30T10:00:00+01:00");
        when(prices.prices(any())).thenAnswer(inv -> {
            FlightQuery q = inv.getArgument(0);
            if (q.departure().month().getMonthValue() == 11) {
                return List.of(offer("SS", "200", "2026-11-10T10:00:00+01:00"), // avant la période
                        shared, offer("TU", "350", "2026-11-26T10:00:00+01:00"));
            }
            return List.of(offer("ET", "250", "2026-12-05T10:00:00+01:00"),
                    offer("KL", "100", "2026-12-06T10:00:00+01:00"), // après la période
                    shared);
        });

        List<TravelpayoutsPrice> found = FlightAlertMatches.inWindow(a, prices, TODAY);

        verify(prices, times(2)).prices(any());
        assertThat(found).extracting(TravelpayoutsPrice::airline).containsExactly("ET", "AF", "TU");
        assertThat(FlightAlertMatches.cheapest(found)).isEqualByComparingTo("250");
    }

    @Test
    void cheapest_emptyIsNull() {
        assertThat(FlightAlertMatches.cheapest(List.of())).isNull();
    }

    @Test
    void underThreshold_isInclusive() {
        List<TravelpayoutsPrice> sorted = List.of(offer("AF", "300", null), offer("SS", "400", null),
                offer("TU", "401", null));

        assertThat(FlightAlertMatches.underThreshold(sorted, new BigDecimal("400.00")))
                .extracting(TravelpayoutsPrice::airline).containsExactly("AF", "SS");
    }

    @Test
    void airlines_distinctUppercase_skipBlank_maxThree() {
        List<TravelpayoutsPrice> offers = List.of(offer("af", "1", null), offer(null, "2", null),
                offer(" ", "3", null), offer("AF", "4", null), offer("ss", "5", null), offer("TU", "6", null),
                offer("ET", "7", null));

        assertThat(FlightAlertMatches.airlines(offers)).containsExactly("AF", "SS", "TU");
        assertThat(FlightAlertMatches.airlines(List.of())).isEmpty();
    }

    @Test
    void record_setsCountAndAirlines_orResetsToZero() {
        FlightPriceAlertEntity a = alert(UUID.randomUUID(), "350");

        FlightAlertMatches.record(a, List.of(offer("AF", "300", null), offer("SS", "340", null),
                offer("TU", "360", null)));
        assertThat(a.getLastMatchCount()).isEqualTo(2);
        assertThat(a.getLastMatchAirlines()).isEqualTo("AF,SS");

        FlightAlertMatches.record(a, List.of(offer("TU", "360", null)));
        assertThat(a.getLastMatchCount()).isZero();
        assertThat(a.getLastMatchAirlines()).isEmpty();
    }

    @Test
    void splitAndJoin_roundTrip() {
        assertThat(FlightAlertMatches.split(FlightAlertMatches.join(List.of("AF", "SS")))).containsExactly("AF", "SS");
        assertThat(FlightAlertMatches.split(null)).isEmpty();
        assertThat(FlightAlertMatches.split("")).isEmpty();
        assertThat(FlightAlertMatches.split("AF, ,SS")).containsExactly("AF", "SS");
    }
}
