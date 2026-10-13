package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

class FlightSearchDiagnosticTest {

    private static final FlightQuery HOLIDAYS =
            FlightQuery.of("PAR", "ABJ", "2026-11-29", "2026-12-27", true, "XOF", CLOCK);

    private static FlightQuery months(String out, String back) {
        return new FlightQuery("PAR", "ABJ", FlightDate.parse(out), back == null ? null : FlightDate.parse(back),
                true, "XOF");
    }

    private static TravelpayoutsPrice trip(String out, String back) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", out + "T11:30:00+01:00",
                back == null ? null : back + "T09:00:00+00:00", BigDecimal.TEN, "AF", "702", 0, 0, 900, "Gate", "/s");
    }

    @Test
    void line_reportsFullPages_failedPairs_andNoNearbyWindow() {
        FlightQuery novDec = months("2026-11", "2026-12");
        FlightQuery decDec = months("2026-12", "2026-12");
        List<TravelpayoutsPrice> full = new ArrayList<>();
        for (int i = 0; i < FlightPriceService.MONTH_LIMIT; i++) full.add(trip("2026-11-02", "2026-12-01"));
        full.add(trip("2026-11-28", "2026-12-28"));
        full.add(trip("2026-11-30", null)); // sans retour : compte dans raw, pas dans les séjours
        Map<FlightQuery, List<TravelpayoutsPrice>> byMonth = new LinkedHashMap<>();
        byMonth.put(novDec, full);

        String line = FlightSearchDiagnostic.line(HOLIDAYS, 2, null, 2, 0, List.of(novDec, decDec), byMonth);

        assertThat(line).isEqualTo("[FlightSearchDiag] route=PAR-ABJ dates=2026-11-29/2026-12-27 direct=true"
                + " currency=XOF exact=2 nearbyWindow=- offers=2 similarOffers=0 stay=28 similarStay=21-35"
                + " months=2026-11×2026-12{raw=1002 full=true w3=1 w7=1 dep=2026-11-02..2026-11-30"
                + " ret=2026-12-01..2026-12-28 stays=29/29/30}; 2026-12×2026-12{failed}");
    }

    @Test
    void line_withoutMonthReads_orStay() {
        FlightQuery oneWay = FlightQuery.of("PAR", "ABJ", "2026-11-29", null, false, "EUR", CLOCK);

        assertThat(FlightSearchDiagnostic.line(oneWay, 0, 3, 1, 0, List.of(), Map.of()))
                .isEqualTo("[FlightSearchDiag] route=PAR-ABJ dates=2026-11-29/null direct=false currency=EUR"
                        + " exact=0 nearbyWindow=3 offers=1 similarOffers=0 months=-");
    }
}
