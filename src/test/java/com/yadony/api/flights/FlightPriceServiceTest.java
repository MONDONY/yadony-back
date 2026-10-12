package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Lecture multi-pages + appel des directs, fusion, dédoublonnage et tolérance aux pannes. */
class FlightPriceServiceTest {

    private static final Executor SAME_THREAD = Runnable::run;

    private final TravelpayoutsClient client = mock(TravelpayoutsClient.class);
    private final FlightQuery query = new FlightQuery("PAR", "ABJ", null, null, false, "EUR");
    private final FlightQuery directQuery = new FlightQuery("PAR", "ABJ", null, null, true, "EUR");

    private FlightPriceService service() {
        return new FlightPriceService(client, Duration.ofMinutes(30), SAME_THREAD);
    }

    private static TravelpayoutsPrice offer(String id, String amount, int transfers) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", "2026-12-04T11:30:00+01:00", null,
                new BigDecimal(amount), "XX", id, transfers, null, 400, "gate", "/search/PAR0412ABJ1?t=" + id);
    }

    /** {@code count} correspondances à partir du prix {@code from}, identifiants préfixés. */
    private static List<TravelpayoutsPrice> connections(String prefix, int from, int count) {
        return IntStream.range(0, count).mapToObj(i -> offer(prefix + i, String.valueOf(from + i), 1)).toList();
    }

    private void stubPage(boolean direct, int page, List<TravelpayoutsPrice> rows) {
        when(client.pricesForDates(eq("PAR"), eq("ABJ"), any(), any(), eq(direct), eq("EUR"),
                eq(FlightPriceService.PAGE_SIZE), eq(page))).thenReturn(rows);
    }

    @Test
    void prices_mergesTwoPagesAndDirects_sortedByPrice() {
        TravelpayoutsPrice airFrance = new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", "2026-12-04T13:00:00+01:00",
                null, new BigDecimal("780"), "AF", "702", 0, null, 380, "Air France", "/search/PAR0412ABJ1?t=AF702");
        stubPage(false, 1, connections("a", 300, 50));
        stubPage(false, 2, connections("b", 400, 20));
        stubPage(true, 1, List.of(airFrance));

        List<TravelpayoutsPrice> result = service().prices(query);

        assertThat(result).hasSize(71);
        assertThat(result).contains(airFrance);
        assertThat(result).isSortedAccordingTo((x, y) -> x.price().compareTo(y.price()));
        verify(client, never()).pricesForDates(anyString(), anyString(), any(), any(), anyBoolean(), anyString(),
                anyInt(), eq(3));
    }

    @Test
    void prices_shortFirstPage_doesNotAskForPage2() {
        stubPage(false, 1, connections("a", 300, 10));
        stubPage(true, 1, List.of());

        assertThat(service().prices(query)).hasSize(10);
        verify(client, never()).pricesForDates(anyString(), anyString(), any(), any(), anyBoolean(), anyString(),
                anyInt(), eq(2));
    }

    @Test
    void prices_duplicateFromDirectCall_isKeptOnce() {
        TravelpayoutsPrice direct = offer("d1", "500", 0);
        stubPage(false, 1, List.of(offer("a", "300", 1), direct));
        stubPage(true, 1, List.of(direct));

        assertThat(service().prices(query)).extracting(TravelpayoutsPrice::flightNumber).containsExactly("a", "d1");
    }

    @Test
    void prices_directFailure_keepsMainResults() {
        stubPage(false, 1, connections("a", 300, 3));
        when(client.pricesForDates(anyString(), anyString(), any(), any(), eq(true), anyString(), anyInt(), anyInt()))
                .thenThrow(new FlightProviderException("down"));

        assertThat(service().prices(query)).hasSize(3);
    }

    @Test
    void prices_directFailureOnAnotherThread_keepsMainResults() {
        stubPage(false, 1, connections("a", 300, 3));
        when(client.pricesForDates(anyString(), anyString(), any(), any(), eq(true), anyString(), anyInt(), anyInt()))
                .thenThrow(new FlightProviderException("down"));
        FlightPriceService parallel = new FlightPriceService(client, Duration.ofMinutes(30),
                Executors.newVirtualThreadPerTaskExecutor());

        assertThat(parallel.prices(query)).hasSize(3);
    }

    @Test
    void prices_page2Failure_keepsPage1AndDirects() {
        stubPage(false, 1, connections("a", 300, 50));
        when(client.pricesForDates(anyString(), anyString(), any(), any(), eq(false), anyString(), anyInt(), eq(2)))
                .thenThrow(new FlightProviderException("down"));
        stubPage(true, 1, List.of(offer("d", "900", 0)));

        assertThat(service().prices(query)).hasSize(51);
    }

    @Test
    void prices_page1Failure_failsTheSearch_andIsNotCached() {
        stubPage(true, 1, List.of(offer("d", "900", 0)));
        when(client.pricesForDates(anyString(), anyString(), any(), any(), eq(false), anyString(), anyInt(), eq(1)))
                .thenThrow(new FlightProviderException("down"))
                .thenReturn(List.of(offer("a", "300", 1)));
        FlightPriceService service = service();

        assertThatThrownBy(() -> service.prices(query)).isInstanceOf(FlightProviderException.class);
        assertThat(service.prices(query)).hasSize(2);
    }

    @Test
    void prices_directOnly_makesNoExtraDirectCall_butReadsPage2() {
        stubPage(true, 1, connections("d", 300, 50).stream()
                .map(p -> offer(p.flightNumber(), p.price().toPlainString(), 0)).toList());
        stubPage(true, 2, List.of(offer("z", "999", 0)));

        assertThat(service().prices(directQuery)).hasSize(51);
        verify(client, times(2)).pricesForDates(anyString(), anyString(), any(), any(), anyBoolean(), anyString(),
                anyInt(), anyInt());
    }

    @Test
    void prices_resultIsCached() {
        stubPage(false, 1, connections("a", 300, 2));
        stubPage(true, 1, List.of());
        FlightPriceService service = service();

        service.prices(query);
        service.prices(query);

        verify(client, times(2)).pricesForDates(anyString(), anyString(), any(), any(), anyBoolean(), anyString(),
                anyInt(), anyInt());
    }

    @Test
    void merge_truncation_neverEvictsDirects() {
        List<TravelpayoutsPrice> rows = new ArrayList<>(connections("a", 100, 200));
        TravelpayoutsPrice expensiveDirect = offer("af", "2000", 0);
        rows.add(expensiveDirect);

        List<TravelpayoutsPrice> merged = FlightPriceService.merge(rows);

        assertThat(merged).hasSize(FlightPriceService.MAX_RESULTS);
        assertThat(merged).contains(expensiveDirect);
        assertThat(merged.get(merged.size() - 1)).isEqualTo(expensiveDirect);
        assertThat(merged.get(merged.size() - 2).price()).isEqualByComparingTo("248");
    }

    @Test
    void merge_dropsZeroAndNullPrices_andKeepsCheapestDuplicate() {
        TravelpayoutsPrice zero = offer("z", "0", 1);
        TravelpayoutsPrice noPrice = new TravelpayoutsPrice("PAR", "ABJ", null, null, null, null, null,
                null, null, null, null, null, null, null);
        TravelpayoutsPrice dearer = offer("same", "400", 1);
        TravelpayoutsPrice cheaper = offer("same", "350", 1);
        List<TravelpayoutsPrice> rows = new ArrayList<>(List.of(zero, dearer, cheaper));
        rows.add(noPrice);
        rows.add(null);

        assertThat(FlightPriceService.merge(rows)).containsExactly(cheaper);
    }

    @Test
    void dedupKey_fallsBackToFlightIdentity_whenLinkIsMissing() {
        TravelpayoutsPrice a = new TravelpayoutsPrice("PAR", "ABJ", null, null, "2026-12-04T13:00", "2027-01-08T10:00",
                new BigDecimal("700"), "AF", "702", 0, 0, null, null, null);
        TravelpayoutsPrice b = new TravelpayoutsPrice("PAR", "ABJ", null, null, "2026-12-04T13:00", "2027-01-08T10:00",
                new BigDecimal("690"), "AF", "702", 0, 0, null, null, " ");

        assertThat(FlightPriceService.dedupKey(a)).isEqualTo(FlightPriceService.dedupKey(b));
        assertThat(FlightPriceService.merge(List.of(a, b))).containsExactly(b);
    }

    @Test
    void isDirect_requiresNoTransferEitherWay() {
        assertThat(FlightPriceService.isDirect(offer("a", "1", 0))).isTrue();
        assertThat(FlightPriceService.isDirect(offer("a", "1", 1))).isFalse();
        TravelpayoutsPrice returnWithStop = new TravelpayoutsPrice("PAR", "ABJ", null, null, null, null,
                BigDecimal.ONE, null, null, 0, 1, null, null, null);
        TravelpayoutsPrice unknown = new TravelpayoutsPrice("PAR", "ABJ", null, null, null, null,
                BigDecimal.ONE, null, null, null, null, null, null, null);
        assertThat(FlightPriceService.isDirect(returnWithStop)).isFalse();
        assertThat(FlightPriceService.isDirect(unknown)).isFalse();
    }
}
