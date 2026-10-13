package com.yadony.api.flights;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Recherche aller-retour en jours précis, de bout en bout jusqu'au client Travelpayouts simulé :
 * le faux client se comporte comme {@code prices_for_dates} (filtre jour ou mois sur
 * {@code departure_at} / {@code return_at}, {@code direct}, tri par prix, {@code limit} et {@code page}).
 *
 * <p>Régression signalée sur appareil : Paris ⇄ Abidjan du 29/11 au 27/12 rendait « Aucun vol à ces
 * dates » alors que le cache contenait des allers-retours à un ou deux jours près.
 */
class FlightSearchNearbyDatesCacheTest {

    private static final Executor SAME_THREAD = Runnable::run;

    private final TravelpayoutsClient client = mock(TravelpayoutsClient.class);
    private final FlightUsers users = mock(FlightUsers.class);
    private final List<TravelpayoutsPrice> cache = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private FlightSearchService service;

    @BeforeEach
    void setUp() {
        when(client.isConfigured()).thenReturn(true);
        when(users.resolve(any())).thenReturn(new FlightUsers.Caller(null, "fr"));
        when(client.pricesForDates(anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyInt(), anyInt()))
                .thenAnswer(inv -> answer(inv.getArgument(2), inv.getArgument(3), inv.getArgument(4),
                        inv.getArgument(6), inv.getArgument(7)));
        FlightPriceService prices = new FlightPriceService(client, Duration.ofMinutes(30), SAME_THREAD);
        FlightOfferMapper mapper = new FlightOfferMapper(mock(AirlineDirectory.class), mock(AirportTimeZones.class),
                "https://logos.test/{code}.png");
        service = new FlightSearchService(prices, mapper, users, mock(FlightSearchRepository.class), CLOCK);
    }

    /** Ce que ferait {@code prices_for_dates} sur le cache simulé. */
    private List<TravelpayoutsPrice> answer(FlightDate departure, FlightDate returnDate, boolean direct,
                                            int limit, int page) {
        calls.add(departure + "/" + returnDate + (direct ? "/direct" : "") + "/limit=" + limit + "/page=" + page);
        List<TravelpayoutsPrice> rows = cache.stream()
                .filter(p -> matches(departure, p.departureAt()) && matches(returnDate, p.returnAt()))
                .filter(p -> !direct || FlightPriceService.isDirect(p))
                .sorted(Comparator.comparing(TravelpayoutsPrice::price))
                .toList();
        int from = Math.min(rows.size(), (page - 1) * limit);
        return rows.subList(from, Math.min(rows.size(), from + limit));
    }

    private static boolean matches(FlightDate wanted, String providerDateTime) {
        return wanted == null || (providerDateTime != null && providerDateTime.startsWith(wanted.providerValue()));
    }

    private static TravelpayoutsPrice trip(String out, String back, int price, int transfers, String id) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", out + "T11:30:00+01:00", back + "T09:00:00+00:00",
                BigDecimal.valueOf(price), "AT", id, transfers, transfers, 900, "Kiwi.com", "/search/" + id);
    }

    /**
     * Le mois de novembre × décembre contient plus de 100 allers-retours moins chers que ceux de la
     * période des fêtes : début novembre, retour début décembre.
     */
    private void cheapEarlyNovemberTrips(int count) {
        for (int i = 0; i < count; i++) {
            String out = "2026-11-" + String.format("%02d", 2 + i % 14);
            String back = "2026-12-" + String.format("%02d", 1 + i % 12);
            cache.add(trip(out, back, 300 + i, 1, "cheap" + i));
        }
    }

    @Test
    void holidayRoundTrip_acrossTwoMonths_findsOffersAtAFewDaysBothWays() {
        cheapEarlyNovemberTrips(120);
        cache.add(trip("2026-11-28", "2026-12-26", 780, 1, "near1"));   // -1 / -1
        cache.add(trip("2026-11-30", "2026-12-28", 820, 1, "near2"));   // +1 / +1
        cache.add(trip("2026-12-01", "2026-12-27", 760, 1, "near3"));   // +2 / 0, départ en décembre
        cache.add(trip("2026-11-29", "2027-01-03", 500, 1, "far"));     // retour +7 : écartée

        var response = service.search("uid", "PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR");

        assertThat(response.nearbyDates()).isTrue();
        assertThat(response.nearbyDaysWindow()).isEqualTo(3);
        assertThat(response.offers()).extracting(o -> o.price().toPlainString())
                .containsExactly("760", "780", "820");
    }

    @Test
    void nothingAtThreeDays_widensToAWeek_andSaysSo() {
        cheapEarlyNovemberTrips(120);
        cache.add(trip("2026-12-04", "2027-01-02", 840, 1, "week1"));  // +5 / +6
        cache.add(trip("2026-11-23", "2026-12-21", 690, 1, "week2"));  // -6 / -6
        cache.add(trip("2026-12-08", "2026-12-27", 400, 1, "tooFar"));  // +9 : écartée

        var response = service.search("uid", "PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR");

        assertThat(response.nearbyDates()).isTrue();
        assertThat(response.nearbyDaysWindow()).isEqualTo(7);
        assertThat(response.offers()).extracting(o -> o.price().toPlainString()).containsExactly("690", "840");
        assertThat(response.searchPath()).isEqualTo("/search/PAR2911ABJ27121");
    }

    @Test
    void exactPair_inCache_isReturnedWithoutFallback() {
        cheapEarlyNovemberTrips(10);
        cache.add(trip("2026-11-29", "2026-12-27", 900, 1, "exact"));

        var response = service.search("uid", "PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR");

        assertThat(response.nearbyDates()).isNull();
        assertThat(response.offers()).extracting(o -> o.price().toPlainString()).containsExactly("900");
        assertThat(calls).allMatch(c -> c.startsWith("2026-11-29/2026-12-27/"));
    }

    @Test
    void oneWay_departureWindowCrossingIntoNextMonth_isFound() {
        cache.add(new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", "2026-12-01T11:30:00+01:00", null,
                BigDecimal.valueOf(410), "AF", "702", 0, null, 380, "Air France", "/search/af"));

        var response = service.search("uid", "PAR", "ABJ", "2026-11-29", null, false, "EUR");

        assertThat(response.nearbyDates()).isTrue();
        assertThat(response.nearbyDaysWindow()).isEqualTo(3);
        assertThat(response.offers()).extracting(o -> o.price().toPlainString()).containsExactly("410");
    }
}
