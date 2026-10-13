package com.yadony.api.flights;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static com.yadony.api.flights.FlightTestData.price;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightSearchServiceTest {

    private static final String LOGO_TEMPLATE = "https://logos.test/{code}.png";

    @Mock FlightPriceService prices;
    @Mock AirlineDirectory airlines;
    @Mock AirportTimeZones timeZones;
    @Mock FlightUsers users;
    @Mock FlightSearchRepository searches;

    FlightSearchService service;
    final UUID owner = UUID.randomUUID();
    static final String UID = "uid-search";

    @BeforeEach
    void setUp() {
        service = new FlightSearchService(prices, new FlightOfferMapper(airlines, timeZones, LOGO_TEMPLATE), users, searches, CLOCK);
        lenient().when(prices.isAvailable()).thenReturn(true);
        lenient().when(users.resolve(UID)).thenReturn(new FlightUsers.Caller(owner, "en"));
        lenient().when(airlines.nameOf(anyString(), anyString())).thenAnswer(inv ->
                Map.of("AT", "Royal Air Maroc").getOrDefault(inv.<String>getArgument(0), inv.getArgument(0)));
    }

    @Test
    void search_returnsMappedOffers_andRemembersTheRoute() {
        when(prices.prices(any())).thenReturn(List.of(price("327"), price("410")));

        var response = service.search(UID, "par", "abj", "2026-12", "2027-01", false, "eur");

        assertThat(response.originCode()).isEqualTo("PAR");
        assertThat(response.roundTrip()).isTrue();
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.offers()).hasSize(2);
        assertThat(response.searchPath()).isEqualTo("/search/PAR0112ABJ01011");
        var first = response.offers().get(0);
        assertThat(first.airlineName()).isEqualTo("Royal Air Maroc");
        assertThat(first.currency()).isEqualTo("EUR");
        assertThat(first.offerPath()).startsWith("/search/");
        assertThat(first.durationMinutes()).isEqualTo(580);
        assertThat(first.logoUrl()).isEqualTo("https://logos.test/AT.png");

        ArgumentCaptor<FlightSearchEntity> saved = ArgumentCaptor.forClass(FlightSearchEntity.class);
        verify(searches).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo(owner);
        assertThat(saved.getValue().getDestinationCode()).isEqualTo("ABJ");
        assertThat(saved.getValue().isRoundTrip()).isTrue();
    }

    @Test
    void search_providerDown_is502_andNothingIsRemembered() {
        when(prices.prices(any())).thenThrow(new FlightProviderException("down"));

        assertThatThrownBy(() -> service.search(UID, "PAR", "ABJ", null, null, false, null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-provider-error");
        verify(searches, never()).save(any());
    }

    @Test
    void search_rememberFailure_doesNotFailTheSearch() {
        when(prices.prices(any())).thenReturn(List.of(price("327")));
        when(searches.save(any())).thenThrow(new RuntimeException("db down"));

        var response = service.search(UID, "PAR", "ABJ", null, null, false, null);
        assertThat(response.offers()).hasSize(1);
        assertThat(response.searchPath()).isNull();
    }

    @Test
    void search_unconfigured_is503_beforeAnyValidation() {
        when(prices.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.search(UID, "bad", "bad", null, null, false, null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-unavailable");
    }

    @Test
    void search_guest_isAnonymous_andNothingIsRemembered() {
        when(users.resolve("guest")).thenReturn(new FlightUsers.Caller(null, "fr"));
        when(prices.prices(any())).thenReturn(List.of(price("327")));

        var response = service.search("guest", "PAR", "ABJ", "2026-12-12", null, false, null);

        assertThat(response.offers()).hasSize(1);
        assertThat(response.searchPath()).isEqualTo("/search/PAR1212ABJ1");
        verifyNoInteractions(searches);
    }

    /** Régression : ABJ ⇄ PAR du 23/12 au 02/01 en jours précis rendait 0 offre (couple absent du cache Aviasales). */
    @Test
    @SuppressWarnings("unchecked")
    void exactDays_withoutOffers_fallBackToNearbyDatesInTheMonths_sortedByPrice() {
        when(prices.prices(any())).thenReturn(List.of());
        stubMonths(
                FlightNearbyDatesTest.offer("2026-12-24T23:55:00+00:00", "2027-01-02T09:00:00+01:00", "650"),
                FlightNearbyDatesTest.offer("2026-12-10T10:00:00+00:00", "2027-01-02T09:00:00+01:00", "300"),
                FlightNearbyDatesTest.offer("2026-12-23T10:00:00+00:00", "2027-01-04T09:00:00+01:00", "600"));

        var response = service.search(UID, "ABJ", "PAR", "2026-12-23", "2027-01-02", false, "EUR");

        assertThat(response.nearbyDates()).isTrue();
        assertThat(response.nearbyDaysWindow()).isEqualTo(3);
        assertThat(response.offers()).extracting(o -> o.price().toPlainString()).containsExactly("600", "650");
        assertThat(response.offers().get(0).airlineCode()).isEqualTo("SS");
        assertThat(response.searchPath()).isEqualTo("/search/ABJ2312PAR02011");
        assertThat(response.similarOffers()).isNull(); // séjour de 23 j pour 10 demandés : hors plage
        assertThat(response.similarStayMinDays()).isNull();

        ArgumentCaptor<FlightQuery> exact = ArgumentCaptor.forClass(FlightQuery.class);
        verify(prices).prices(exact.capture());
        assertThat(exact.getValue().departure().providerValue()).isEqualTo("2026-12-23");
        assertThat(exact.getValue().returnDate().providerValue()).isEqualTo("2027-01-02");
        ArgumentCaptor<List<FlightQuery>> months = ArgumentCaptor.forClass(List.class);
        verify(prices, atLeastOnce()).pricesByMonth(months.capture());
        assertThat(months.getAllValues().get(0)).extracting(q -> q.departure() + "/" + q.returnDate())
                .containsExactly("2026-12/2026-12", "2026-12/2027-01");
        verify(searches).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactDay_oneWay_widensOnlyTheDeparture() {
        when(prices.prices(any())).thenReturn(List.of());
        stubMonths(FlightNearbyDatesTest.offer("2026-12-21T10:00:00+00:00", null, "420"));

        var response = service.search(UID, "ABJ", "PAR", "2026-12-23", null, false, "EUR");

        assertThat(response.nearbyDates()).isTrue();
        assertThat(response.offers()).hasSize(1);
        assertThat(response.similarOffers()).isNull();
        assertThat(response.similarStayMinDays()).isNull();
        assertThat(response.similarStayMaxDays()).isNull();
        ArgumentCaptor<List<FlightQuery>> months = ArgumentCaptor.forClass(List.class);
        verify(prices).pricesByMonth(months.capture());
        assertThat(months.getValue()).singleElement().satisfies(q -> {
            assertThat(q.departure().providerValue()).isEqualTo("2026-12");
            assertThat(q.returnDate()).isNull();
        });
    }

    @Test
    void exactDays_withEnoughOffers_doNotFallBack_norLookForSimilarStays() {
        when(prices.prices(any())).thenReturn(List.of(price("327"), price("330"), price("340"), price("350"),
                price("360")));

        var response = service.search(UID, "PAR", "ABJ", "2026-12-12", "2027-01-02", false, null);

        assertThat(response.offers()).hasSize(5);
        assertThat(response.nearbyDates()).isNull();
        assertThat(response.nearbyDaysWindow()).isNull();
        assertThat(response.similarOffers()).isNull();
        assertThat(response.similarStayMinDays()).isNull();
        verify(prices, times(1)).prices(any());
        verify(prices, never()).pricesByMonth(any());
    }

    @Test
    void exactDays_withAFewOffers_doNotFallBack_butLookForSimilarStays() {
        when(prices.prices(any())).thenReturn(List.of(price("327")));
        stubMonths();

        var response = service.search(UID, "PAR", "ABJ", "2026-12-12", "2027-01-02", false, null);

        assertThat(response.offers()).hasSize(1);
        assertThat(response.nearbyDates()).isNull();
        assertThat(response.similarOffers()).isNull(); // rien trouvé : absent, plage comprise
        assertThat(response.similarStayMinDays()).isNull();
        verify(prices, times(1)).pricesByMonth(any());
    }

    @Test
    void wholeMonths_withoutOffers_doNotFallBack() {
        when(prices.prices(any())).thenReturn(List.of());

        var response = service.search(UID, "PAR", "ABJ", "2026-12", "2027-01", false, null);

        assertThat(response.offers()).isEmpty();
        assertThat(response.nearbyDates()).isNull();
        verify(prices, times(1)).prices(any());
        verify(prices, never()).pricesByMonth(any());
    }

    @Test
    void nearbyDates_nothingWithinAWeek_staysEmptyWithoutFlag() {
        when(prices.prices(any())).thenReturn(List.of());
        stubMonths(FlightNearbyDatesTest.offer("2026-12-01T10:00:00+00:00", "2027-01-02T09:00:00+01:00", "300"));

        var response = service.search(UID, "ABJ", "PAR", "2026-12-23", "2027-01-02", false, "EUR");

        assertThat(response.offers()).isEmpty();
        assertThat(response.nearbyDates()).isNull();
        assertThat(response.nearbyDaysWindow()).isNull();
        // La fenêtre à 7 jours touche les mêmes couples de mois : pas de second appel pour le repli.
        assertThat(askedMonths()).startsWith("2026-12/2026-12", "2026-12/2027-01").doesNotHaveDuplicates()
                .hasSizeLessThanOrEqualTo(FlightSearchService.MAX_MONTH_QUERIES);
    }

    @Test
    void nearbyDates_nothingInTheMonths_isNotAnError() {
        when(prices.prices(any())).thenReturn(List.of());
        stubMonths();

        var response = service.search(UID, "ABJ", "PAR", "2026-12-23", "2027-01-02", false, "EUR");

        assertThat(response.offers()).isEmpty();
        assertThat(response.nearbyDates()).isNull();
    }

    // ---------------------------------------------------------------------------------------------
    // Séjours de durée proche et ligne de diagnostic
    // ---------------------------------------------------------------------------------------------

    /** Le partenaire simulé : chaque recherche au mois rend les offres dont l'aller et le retour tombent dans ses mois. */
    private void stubMonths(TravelpayoutsPrice... rows) {
        when(prices.pricesByMonth(any())).thenAnswer(inv -> {
            List<FlightQuery> queries = inv.getArgument(0);
            Map<FlightQuery, List<TravelpayoutsPrice>> byMonth = new LinkedHashMap<>();
            for (FlightQuery q : queries) {
                byMonth.put(q, Arrays.stream(rows)
                        .filter(p -> inMonth(q.departure(), p.departureAt()) && inMonth(q.returnDate(), p.returnAt()))
                        .toList());
            }
            return byMonth;
        });
    }

    private static boolean inMonth(FlightDate month, String providerDateTime) {
        return month == null || (providerDateTime != null && providerDateTime.startsWith(month.providerValue()));
    }

    /** Tous les couples de mois demandés au partenaire, dans l'ordre, appels confondus. */
    @SuppressWarnings("unchecked")
    private List<String> askedMonths() {
        ArgumentCaptor<List<FlightQuery>> months = ArgumentCaptor.forClass(List.class);
        verify(prices, atLeast(0)).pricesByMonth(months.capture());
        return months.getAllValues().stream().flatMap(List::stream)
                .map(q -> q.departure() + "/" + q.returnDate()).toList();
    }

    private static TravelpayoutsPrice trip(String out, String back, String amount) {
        return trip(out, back, amount, 1);
    }

    private static TravelpayoutsPrice trip(String out, String back, String amount, int transfers) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", out + "T11:30:00+01:00", back + "T09:00:00+00:00",
                new BigDecimal(amount), "AF", "702", transfers, transfers, 900, "Gate", "/search/" + out + back + amount);
    }

    /**
     * Cas signalé : Paris ⇄ Abidjan du 29/11 au 27/12 (28 jours), une seule offre à 7 jours près.
     * Le cache contient d'autres séjours de 21 à 35 jours à d'autres dates.
     */
    private void holidayCache() {
        when(prices.prices(any())).thenReturn(List.of());
        stubMonths(
                trip("2026-11-23", "2026-12-21", "690"),  // -6 / -6 : trouvée par le repli à 7 jours (28 j)
                trip("2026-11-15", "2026-12-10", "400"),  // 25 j
                trip("2026-11-01", "2026-11-30", "350"),  // 29 j, retour en novembre
                trip("2026-10-20", "2026-11-20", "500"),  // 31 j, mois précédent pas encore passé
                trip("2026-11-10", "2026-12-26", "300"),  // 46 j : hors plage
                trip("2026-11-20", "2026-12-08", "310"),  // 18 j : hors plage
                trip("2027-01-05", "2027-02-01", "200")); // départ deux mois après : hors fenêtre
    }

    @Test
    void fewRoundTripOffers_proposeStaysOfSimilarLength_sortedByPrice_withoutTheOffersAlreadyShown() {
        holidayCache();

        var response = service.search(UID, "PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR");

        assertThat(response.nearbyDaysWindow()).isEqualTo(7);
        assertThat(response.offers()).extracting(o -> o.price().toPlainString()).containsExactly("690");
        assertThat(response.similarOffers()).extracting(o -> o.price().toPlainString())
                .containsExactly("350", "400", "500");
        assertThat(response.similarOffers()).allSatisfy(o -> {
            assertThat(o.currency()).isEqualTo("EUR");
            assertThat(o.airlineName()).isEqualTo("AF");
            assertThat(o.offerPath()).startsWith("/search/");
        });
        assertThat(response.similarStayMinDays()).isEqualTo(21);
        assertThat(response.similarStayMaxDays()).isEqualTo(35);
    }

    @Test
    void similarStays_reuseTheNearbyMonths_andStayWithinTheCallBudget() {
        holidayCache();

        service.search(UID, "PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR");

        assertThat(askedMonths())
                .doesNotHaveDuplicates()
                .hasSize(FlightSearchService.MAX_MONTH_QUERIES)
                .startsWith("2026-11/2026-12", "2026-12/2026-12", "2026-11/2027-01", "2026-12/2027-01")
                .contains("2026-10/2026-11", "2026-11/2026-11");
    }

    @Test
    void similarStays_keepTheDirectFilterOfTheSearch() {
        when(prices.prices(any())).thenReturn(List.of());
        stubMonths(trip("2026-11-15", "2026-12-10", "400", 1), trip("2026-11-16", "2026-12-12", "450", 0));

        var response = service.search(UID, "PAR", "ABJ", "2026-11-29", "2026-12-27", true, "EUR");

        assertThat(response.similarOffers()).extracting(o -> o.price().toPlainString()).containsExactly("450");
    }

    @Test
    void similarStays_failure_neverFailsTheSearch() {
        when(prices.prices(any())).thenReturn(List.of(price("327")));
        when(prices.pricesByMonth(any())).thenThrow(new IllegalStateException("boom"));

        var response = service.search(UID, "PAR", "ABJ", "2026-12-12", "2027-01-02", false, null);

        assertThat(response.offers()).hasSize(1);
        assertThat(response.similarOffers()).isNull();
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FlightSearchService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FlightSearchService.class))
                .detachAppender(appender);
    }

    private static List<String> diagnostics(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith(FlightSearchDiagnostic.PREFIX))
                .peek(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private FlightSearchService diagnosing() {
        return new FlightSearchService(prices, new FlightOfferMapper(airlines, timeZones, LOGO_TEMPLATE), users,
                searches, CLOCK, true);
    }

    @Test
    void diagnostic_fewRoundTripOffers_logsOneWarnLine_perMonthPair() {
        holidayCache();
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            diagnosing().search(UID, "PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR");

            assertThat(diagnostics(appender)).singleElement().satisfies(line -> {
                assertThat(line).startsWith("[FlightSearchDiag] route=PAR-ABJ dates=2026-11-29/2026-12-27"
                        + " direct=false currency=EUR exact=0 nearbyWindow=7 offers=1 similarOffers=3"
                        + " stay=28 similarStay=21-35 months=2026-11×2026-12{raw=4 full=false w3=0 w7=1"
                        + " dep=2026-11-10..2026-11-23 ret=2026-12-08..2026-12-26 stays=18/25/46}; ");
                assertThat(line).contains("; 2026-12×2026-12{raw=0 full=false w3=0 w7=0 dep=- ret=- stays=-}")
                        .contains("2026-10×2026-11{raw=1 full=false w3=0 w7=0"
                                + " dep=2026-10-20..2026-10-20 ret=2026-11-20..2026-11-20 stays=31/31/31}")
                        .doesNotContain(UID).doesNotContain(owner.toString())
                        .doesNotContain("\n");
            });
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void diagnostic_isSilent_withFiveOffers_oneWay_monthSearch_orWhenDisabled() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            when(prices.prices(any())).thenReturn(List.of(price("327"), price("330"), price("340"), price("350"),
                    price("360")));
            diagnosing().search(UID, "PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR");

            when(prices.prices(any())).thenReturn(List.of(price("327")));
            diagnosing().search(UID, "PAR", "ABJ", "2026-11-29", null, false, "EUR");
            diagnosing().search(UID, "PAR", "ABJ", "2026-11", "2026-12", false, "EUR");

            holidayCache();
            service.search(UID, "PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR");

            assertThat(diagnostics(appender)).isEmpty();
        } finally {
            detachAppender(appender);
        }
    }
}
