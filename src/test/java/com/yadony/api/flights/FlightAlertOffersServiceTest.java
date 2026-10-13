package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.flights.dto.FlightAlertResponse;
import com.yadony.api.flights.dto.FlightOfferDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static com.yadony.api.flights.FlightTestData.alert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightAlertOffersServiceTest {

    static final String UID = "uid-offers";
    /** Exécute la tâche sur le fil appelant : les tests restent déterministes. */
    static final Executor DIRECT = Runnable::run;

    @Mock FlightPriceAlertRepository alerts;
    @Mock FlightPriceService prices;
    @Mock AirlineDirectory airlines;
    @Mock AirportTimeZones timeZones;
    @Mock FlightUsers users;

    FlightAlertOffersService service;
    final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = service(DIRECT, Duration.ofSeconds(4));
        lenient().when(prices.isAvailable()).thenReturn(true);
        lenient().when(users.resolveAccount(UID)).thenReturn(new FlightUsers.Caller(owner, "fr"));
        lenient().when(airlines.nameOf(any(), anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private FlightAlertOffersService service(Executor executor, Duration wait) {
        return new FlightAlertOffersService(alerts, prices, new FlightOfferMapper(airlines, timeZones, ""), users,
                CLOCK, executor, wait);
    }

    static TravelpayoutsPrice offer(String airline, String amount, String departureAt) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", departureAt, null, new BigDecimal(amount),
                airline, "1", 0, null, 400, "Partner", "/search/X?t=" + airline + amount + departureAt);
    }

    static TravelpayoutsPrice offer(String airline, String amount) {
        return offer(airline, amount, "2026-12-04T11:30:00+01:00");
    }

    private FlightPriceAlertEntity owned(String max) {
        FlightPriceAlertEntity a = alert(owner, max);
        when(alerts.findByIdAndOwnerId(a.getId(), owner)).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void offers_underThreshold_sortedByPrice_withAlertContext() {
        FlightPriceAlertEntity a = owned("400");
        a.setDepartureFrom(LocalDate.of(2026, 12, 1));
        a.setDepartureTo(LocalDate.of(2026, 12, 20));
        when(prices.prices(any())).thenReturn(List.of(offer("SS", "390"), offer("AF", "310"), offer("KL", "520"),
                offer("TU", "400")));

        var response = service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW);

        assertThat(response.alertId()).isEqualTo(a.getId());
        assertThat(response.originCode()).isEqualTo("PAR");
        assertThat(response.destinationCode()).isEqualTo("ABJ");
        assertThat(response.originLabel()).isEqualTo("Paris");
        assertThat(response.destinationLabel()).isEqualTo("Abidjan");
        assertThat(response.roundTrip()).isFalse();
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.maxPrice()).isEqualByComparingTo("400");
        assertThat(response.offers()).extracting(FlightOfferDto::airlineCode).containsExactly("AF", "SS", "TU");
        assertThat(response.belowCount()).isEqualTo(3);
        assertThat(response.offers()).allSatisfy(o -> assertThat(o.currency()).isEqualTo("EUR"));
        assertThat(response.searchPath()).isEqualTo("/search/PAR0112ABJ1");
        assertThat(response.checkedAt()).isEqualTo(Instant.parse("2026-10-15T10:00:00Z"));
    }

    @Test
    void offers_limitedToTwenty() {
        FlightPriceAlertEntity a = owned("1000");
        List<TravelpayoutsPrice> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(offer("A" + (char) ('A' + i % 26), String.valueOf(500 - i)));
        when(prices.prices(any())).thenReturn(many);

        var response = service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW);

        assertThat(response.offers()).hasSize(FlightAlertOffersService.MAX_OFFERS);
        assertThat(response.offers().get(0).price()).isEqualByComparingTo("471");
        assertThat(response.offers().get(19).price()).isEqualByComparingTo("490");
    }

    @Test
    void offers_multiMonthWindow_queriesEachMonth_andFiltersExactPeriod_roundTrip() {
        FlightPriceAlertEntity a = owned("400");
        a.setDepartureFrom(LocalDate.of(2026, 11, 25));
        a.setDepartureTo(LocalDate.of(2026, 12, 5));
        a.setReturnFrom(LocalDate.of(2026, 12, 20));
        a.setReturnTo(LocalDate.of(2026, 12, 30));
        when(prices.prices(any())).thenAnswer(inv -> {
            FlightQuery q = inv.getArgument(0);
            String day = q.departure().month().getMonthValue() == 11 ? "2026-11-28" : "2026-12-06";
            return List.of(new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", day + "T10:00:00+01:00",
                    "2026-12-22T10:00:00Z", new BigDecimal("300"), "AF", "1", 0, 0, 800, "P", "/s/" + day));
        });

        var response = service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW);

        verify(prices, times(2)).prices(any());
        assertThat(response.roundTrip()).isTrue();
        assertThat(response.offers()).extracting(FlightOfferDto::departureAt)
                .containsExactly("2026-11-28T10:00:00+01:00");
        assertThat(response.searchPath()).isEqualTo("/search/PAR2511ABJ20121");
    }

    @Test
    void offers_pausedAlert_stillReturnsOffers() {
        FlightPriceAlertEntity a = owned("400");
        a.setActive(false);
        when(prices.prices(any())).thenReturn(List.of(offer("AF", "310")));

        assertThat(service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW).offers()).hasSize(1);
    }

    @Test
    void offers_withoutPeriod_hasNoSearchPath() {
        FlightPriceAlertEntity a = owned("400");
        when(prices.prices(any())).thenReturn(List.of());

        var response = service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW);

        assertThat(response.offers()).isEmpty();
        assertThat(response.searchPath()).isNull();
    }

    @Test
    void offers_expiredAlert_isEmptyWithoutCallingThePartner() {
        FlightPriceAlertEntity a = owned("400");
        a.setDepartureFrom(LocalDate.of(2026, 9, 1));
        a.setDepartureTo(LocalDate.of(2026, 9, 30));

        assertThat(service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW).offers()).isEmpty();
        verify(prices, never()).prices(any());
    }

    @Test
    void offers_otherUsersAlert_is404() {
        UUID id = UUID.randomUUID();
        when(alerts.findByIdAndOwnerId(id, owner)).thenReturn(Optional.empty());

        assertCode(() -> service.offers(UID, id, FlightAlertOffersScope.BELOW), "flight-alert-not-found", 404);
        verifyNoInteractions(prices);
    }

    @Test
    void offers_providerFailure_is502() {
        FlightPriceAlertEntity a = owned("400");
        when(prices.prices(any())).thenThrow(new FlightProviderException("down"));

        assertCode(() -> service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW), "flights-provider-error", 502);
    }

    @Test
    void offers_providerNotConfigured_is503() {
        FlightPriceAlertEntity a = owned("400");
        when(prices.isAvailable()).thenReturn(false);

        assertCode(() -> service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW), "flights-unavailable", 503);
    }

    @Test
    void offers_scopeAll_includesOffersAboveThreshold_sortedByPrice_withBelowCount() {
        FlightPriceAlertEntity a = owned("400");
        a.setDepartureFrom(LocalDate.of(2026, 12, 1));
        a.setDepartureTo(LocalDate.of(2026, 12, 20));
        when(prices.prices(any())).thenReturn(List.of(offer("SS", "390"), offer("AF", "310"), offer("KL", "520"),
                offer("TU", "400"), offer("ET", "455")));

        var response = service.offers(UID, a.getId(), FlightAlertOffersScope.ALL);

        assertThat(response.offers()).extracting(FlightOfferDto::airlineCode)
                .containsExactly("AF", "SS", "TU", "ET", "KL");
        assertThat(response.belowCount()).isEqualTo(3);
        assertThat(response.maxPrice()).isEqualByComparingTo("400");
        assertThat(response.searchPath()).isEqualTo("/search/PAR0112ABJ1");
    }

    @Test
    void offers_scopeAll_noneUnderThreshold_stillReturnsOffers() {
        FlightPriceAlertEntity a = owned("200");
        when(prices.prices(any())).thenReturn(List.of(offer("KL", "520"), offer("AF", "310")));

        var below = service.offers(UID, a.getId(), FlightAlertOffersScope.BELOW);
        var all = service.offers(UID, a.getId(), FlightAlertOffersScope.ALL);

        assertThat(below.offers()).isEmpty();
        assertThat(below.belowCount()).isZero();
        assertThat(all.offers()).extracting(FlightOfferDto::airlineCode).containsExactly("AF", "KL");
        assertThat(all.belowCount()).isZero();
    }

    @Test
    void offers_scopeAll_limitedToTwentyCheapest_belowCountCountsShownOnly() {
        FlightPriceAlertEntity a = owned("480");
        List<TravelpayoutsPrice> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(offer("A" + (char) ('A' + i % 26), String.valueOf(500 - i)));
        when(prices.prices(any())).thenReturn(many);

        var response = service.offers(UID, a.getId(), FlightAlertOffersScope.ALL);

        assertThat(response.offers()).hasSize(FlightAlertOffersService.MAX_OFFERS);
        assertThat(response.offers().get(0).price()).isEqualByComparingTo("471");
        assertThat(response.offers().get(19).price()).isEqualByComparingTo("490");
        // 471..480 : dix vols sous le seuil dans la liste rendue
        assertThat(response.belowCount()).isEqualTo(10);
    }

    @Test
    void offers_scopeAll_expiredAlert_isEmptyWithoutCallingThePartner() {
        FlightPriceAlertEntity a = owned("400");
        a.setDepartureFrom(LocalDate.of(2026, 9, 1));
        a.setDepartureTo(LocalDate.of(2026, 9, 30));

        var response = service.offers(UID, a.getId(), FlightAlertOffersScope.ALL);

        assertThat(response.offers()).isEmpty();
        assertThat(response.belowCount()).isZero();
        verify(prices, never()).prices(any());
    }

    @Test
    void scope_parse_defaultsToBelow_caseInsensitive_andRejectsUnknown() {
        assertThat(FlightAlertOffersScope.parse(null)).isEqualTo(FlightAlertOffersScope.BELOW);
        assertThat(FlightAlertOffersScope.parse("  ")).isEqualTo(FlightAlertOffersScope.BELOW);
        assertThat(FlightAlertOffersScope.parse("below")).isEqualTo(FlightAlertOffersScope.BELOW);
        assertThat(FlightAlertOffersScope.parse(" ALL ")).isEqualTo(FlightAlertOffersScope.ALL);
        assertCode(() -> FlightAlertOffersScope.parse("cheap"), "flights-invalid-scope", 400);
    }

    private static void assertCode(Runnable call, String code, int status) {
        assertThatThrownBy(call::run).isInstanceOf(YadonyBusinessException.class).satisfies(e -> {
            YadonyBusinessException b = (YadonyBusinessException) e;
            assertThat(b.getErrorCode()).isEqualTo(code);
            assertThat(b.getStatus().value()).isEqualTo(status);
        });
    }

    // --- première vérification à la création ---

    private FlightAlertResponse created(FlightPriceAlertEntity a) {
        return FlightAlertService.toResponse(a);
    }

    @Test
    void firstCheck_fillsBestPriceAndMatches_withoutTouchingAntiSpam() {
        FlightPriceAlertEntity a = alert(owner, "400");
        when(alerts.findById(a.getId())).thenReturn(Optional.of(a));
        when(prices.prices(any())).thenReturn(List.of(offer("SS", "390"), offer("AF", "310"), offer("KL", "520")));
        when(alerts.recordFirstCheck(eq(a.getId()), any(), any(), any(), any())).thenReturn(1);

        var response = service.firstCheck(created(a));

        assertThat(response.lastSeenPrice()).isEqualByComparingTo("310");
        assertThat(response.matchCount()).isEqualTo(2);
        assertThat(response.matchAirlineCodes()).containsExactly("AF", "SS");
        assertThat(response.lastCheckedAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 10, 0));
        verify(alerts).recordFirstCheck(a.getId(), new BigDecimal("310"), LocalDateTime.of(2026, 10, 15, 10, 0),
                2, "AF,SS");
        verify(alerts, never()).save(any());
        assertThat(a.getLastNotifiedPrice()).isNull();
    }

    @Test
    void firstCheck_noOffer_recordsZeroAndNoPrice() {
        FlightPriceAlertEntity a = alert(owner, "400");
        when(alerts.findById(a.getId())).thenReturn(Optional.of(a));
        when(prices.prices(any())).thenReturn(List.of());
        when(alerts.recordFirstCheck(eq(a.getId()), any(), any(), any(), any())).thenReturn(1);

        var response = service.firstCheck(created(a));

        assertThat(response.matchCount()).isZero();
        assertThat(response.matchAirlineCodes()).isEmpty();
        assertThat(response.lastSeenPrice()).isNull();
    }

    @Test
    void firstCheck_rowChangedMeanwhile_returnsCreatedUnchanged() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightAlertResponse before = created(a);
        when(alerts.findById(a.getId())).thenReturn(Optional.of(a));
        when(prices.prices(any())).thenReturn(List.of(offer("AF", "310")));
        when(alerts.recordFirstCheck(eq(a.getId()), any(), any(), any(), any())).thenReturn(0);

        assertThat(service.firstCheck(before)).isSameAs(before);
    }

    @Test
    void firstCheck_alreadyCheckedOrGone_doesNothing() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightAlertResponse before = created(a);
        a.setLastCheckedAt(LocalDateTime.of(2026, 10, 15, 9, 0));
        when(alerts.findById(a.getId())).thenReturn(Optional.of(a));
        assertThat(service.firstCheck(before)).isSameAs(before);

        when(alerts.findById(a.getId())).thenReturn(Optional.empty());
        assertThat(service.firstCheck(before)).isSameAs(before);

        verify(prices, never()).prices(any());
    }

    @Test
    void firstCheck_providerFailure_returnsCreated() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightAlertResponse before = created(a);
        when(alerts.findById(a.getId())).thenReturn(Optional.of(a));
        when(prices.prices(any())).thenThrow(new FlightProviderException("down"));

        assertThat(service.firstCheck(before)).isSameAs(before);
        verify(alerts, never()).recordFirstCheck(any(), any(), any(), any(), any());
    }

    @Test
    void firstCheck_providerNotConfigured_skipsTheCheck() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightAlertResponse before = created(a);
        when(prices.isAvailable()).thenReturn(false);

        assertThat(service.firstCheck(before)).isSameAs(before);
        verifyNoInteractions(alerts);
    }

    @Test
    void firstCheck_tooSlow_returnsCreatedAndLetsTheCheckFinish() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightAlertResponse before = created(a);
        List<Runnable> pending = new ArrayList<>();
        FlightAlertOffersService slow = service(pending::add, Duration.ofMillis(10));

        assertThat(slow.firstCheck(before)).isSameAs(before);
        assertThat(pending).hasSize(1);
    }

    @Test
    void firstCheck_executorRejects_returnsCreated() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightAlertResponse before = created(a);
        FlightAlertOffersService broken = service(r -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        }, Duration.ofSeconds(1));

        assertThat(broken.firstCheck(before)).isSameAs(before);
    }

    @Test
    void firstCheck_interrupted_returnsCreatedAndKeepsFlag() {
        FlightPriceAlertEntity a = alert(owner, "400");
        FlightAlertResponse before = created(a);
        FlightAlertOffersService never = service(r -> { }, Duration.ofSeconds(5));

        Thread.currentThread().interrupt();
        try {
            assertThat(never.firstCheck(before)).isSameAs(before);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
