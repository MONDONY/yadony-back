package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.flights.dto.FlightAlertOffersResponse;
import com.yadony.api.flights.dto.FlightAlertResponse;
import com.yadony.api.flights.dto.FlightClickResponse;
import com.yadony.api.flights.dto.FlightDealsResponse;
import com.yadony.api.flights.dto.FlightDefaultsDto;
import com.yadony.api.flights.dto.FlightPlaceDto;
import com.yadony.api.flights.dto.FlightSearchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FlightControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean FlightSearchService searchService;
    @MockBean FlightPlaceService placeService;
    @MockBean FlightDealService dealService;
    @MockBean FlightAlertService alertService;
    @MockBean FlightAlertOffersService alertOffersService;
    @MockBean FlightClickService clickService;
    @MockBean FlightDefaultsService defaultsService;
    @MockBean FlightFeature feature;
    @MockBean FlightUsers users;

    private static final String UID = "uid-flights";

    private static UsernamePasswordAuthenticationToken member() {
        return new UsernamePasswordAuthenticationToken(UID, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    /** Compte inscrit encore sans rôle métier : la fonctionnalité ne demande aucun rôle. */
    private static UsernamePasswordAuthenticationToken memberWithoutRole() {
        return new UsernamePasswordAuthenticationToken(UID, null, List.of());
    }

    private static UsernamePasswordAuthenticationToken guest() {
        return new UsernamePasswordAuthenticationToken("guest-uid", null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
    }

    private static FlightAlertResponse alertResponse() {
        return new FlightAlertResponse(UUID.randomUUID(), "PAR", "ABJ", "Paris", "Abidjan", java.time.LocalDate.of(2026, 12, 1),
                java.time.LocalDate.of(2026, 12, 31), null, null, false,
                new BigDecimal("400.00"), "EUR", true, null, null, LocalDateTime.now(), null, null);
    }

    @Test
    void search_passesQueryToService() throws Exception {
        when(searchService.search(UID, "PAR", "ABJ", "2026-12", null, true, "XOF"))
                .thenReturn(new FlightSearchResponse("PAR", "ABJ", false, "XOF", List.of(
                        new com.yadony.api.flights.dto.FlightOfferDto("PAR", "ABJ", "CDG", "ABJ",
                                "2026-12-01T11:30:00+01:00", null, new BigDecimal("450000"), "XOF", "AF", "Air France",
                                "702", 0, null, 375, "Air France", "/search/PAR0112ABJ1?t=AF702", null,
                                375, null, "2026-12-01T16:45:00Z", null)), "/search/PAR0112ABJ1"));

        mockMvc.perform(get("/flights/search").with(authentication(memberWithoutRole()))
                        .param("origin", "PAR").param("destination", "ABJ")
                        .param("departureDate", "2026-12").param("direct", "true").param("currency", "XOF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.offers").isArray())
                .andExpect(jsonPath("$.searchPath").value("/search/PAR0112ABJ1"))
                .andExpect(jsonPath("$.offers[0].durationMinutes").value(375))
                .andExpect(jsonPath("$.offers[0].outboundDurationMinutes").value(375))
                .andExpect(jsonPath("$.offers[0].arrivalAt").value("2026-12-01T16:45:00Z"))
                .andExpect(jsonPath("$.offers[0].returnArrivalAt").doesNotExist())
                .andExpect(jsonPath("$.nearbyDates").doesNotExist())
                .andExpect(jsonPath("$.nearbyDaysWindow").doesNotExist());
    }

    @Test
    void search_nearbyDatesFallback_isExposed() throws Exception {
        when(searchService.search(UID, "ABJ", "PAR", "2026-12-23", "2027-01-02", false, null))
                .thenReturn(new FlightSearchResponse("ABJ", "PAR", true, "EUR", List.of(),
                        "/search/ABJ2312PAR02011", true, 3));

        mockMvc.perform(get("/flights/search").with(authentication(member()))
                        .param("origin", "ABJ").param("destination", "PAR")
                        .param("departureDate", "2026-12-23").param("returnDate", "2027-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyDates").value(true))
                .andExpect(jsonPath("$.nearbyDaysWindow").value(3))
                .andExpect(jsonPath("$.searchPath").value("/search/ABJ2312PAR02011"));
    }

    @Test
    void search_businessError_isProblemDetail() throws Exception {
        when(searchService.search(eq(UID), any(), any(), isNull(), isNull(), eq(false), isNull()))
                .thenThrow(FlightErrors.unavailable());

        mockMvc.perform(get("/flights/search").with(authentication(member()))
                        .param("origin", "PAR").param("destination", "ABJ"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("flights-unavailable"));
    }

    @Test
    void places_usesCallerLanguage() throws Exception {
        when(users.resolve(UID)).thenReturn(new FlightUsers.Caller(UUID.randomUUID(), "en"));
        when(placeService.suggest("Abid", "en"))
                .thenReturn(List.of(new FlightPlaceDto("ABJ", "Abidjan", "CI", "Ivory Coast")));

        mockMvc.perform(get("/flights/places").with(authentication(member())).param("term", "Abid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ABJ"));
    }

    @Test
    void deals_returnsResponse() throws Exception {
        when(dealService.deals(UID, "EUR")).thenReturn(new FlightDealsResponse("EUR", false, List.of()));

        mockMvc.perform(get("/flights/deals").with(authentication(member())).param("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalized").value(false));
    }

    @Test
    void click_validatesBody() throws Exception {
        mockMvc.perform(post("/flights/clicks").with(authentication(member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerPath\":\"/search/X\",\"originCode\":\"PARIS\",\"destinationCode\":\"ABJ\",\"source\":\"deal\"}"))
                .andExpect(status().is4xxClientError());
        verify(clickService, never()).click(any(), any());
    }

    @Test
    void click_returnsAffiliateUrl() throws Exception {
        when(clickService.click(eq(UID), any())).thenReturn(new FlightClickResponse("https://www.aviasales.com/search/X?marker=1"));

        mockMvc.perform(post("/flights/clicks").with(authentication(member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerPath\":\"/search/X\",\"originCode\":\"PAR\",\"destinationCode\":\"ABJ\","
                                + "\"price\":327,\"currency\":\"EUR\",\"airlineCode\":\"AT\",\"source\":\"search\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://www.aviasales.com/search/X?marker=1"));
    }

    @Test
    void alerts_crud() throws Exception {
        FlightAlertResponse alert = alertResponse();
        when(alertService.list(UID)).thenReturn(List.of(alert));
        when(alertService.create(eq(UID), any())).thenReturn(alert);
        when(alertService.update(eq(UID), eq(alert.id()), any())).thenReturn(alert);
        FlightAlertResponse checked = checkedAlertResponse(alert.id());
        when(alertOffersService.firstCheck(alert)).thenReturn(checked);

        mockMvc.perform(get("/me/flight-alerts").with(authentication(member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].destinationCode").value("ABJ"))
                .andExpect(jsonPath("$[0].matchCount").doesNotExist())
                .andExpect(jsonPath("$[0].matchAirlineCodes").doesNotExist());

        // La création rend l'alerte après sa première vérification silencieuse.
        mockMvc.perform(post("/me/flight-alerts").with(authentication(member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originCode\":\"PAR\",\"destinationCode\":\"ABJ\",\"maxPrice\":400}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchCount").value(4))
                .andExpect(jsonPath("$.matchAirlineCodes[0]").value("AF"))
                .andExpect(jsonPath("$.lastSeenPrice").value(327));

        mockMvc.perform(patch("/me/flight-alerts/" + alert.id()).with(authentication(member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/me/flight-alerts/" + alert.id()).with(authentication(member())))
                .andExpect(status().isNoContent());
        verify(alertService).delete(UID, alert.id());
    }

    private static FlightAlertResponse checkedAlertResponse(UUID id) {
        return new FlightAlertResponse(id, "PAR", "ABJ", "Paris", "Abidjan", java.time.LocalDate.of(2026, 12, 1),
                java.time.LocalDate.of(2026, 12, 31), null, null, false, new BigDecimal("400.00"), "EUR", true,
                new BigDecimal("327"), LocalDateTime.now(), LocalDateTime.now(), 4, List.of("AF", "SS"));
    }

    @Test
    void listAlerts_exposesMatchFields_onceChecked() throws Exception {
        FlightAlertResponse checked = checkedAlertResponse(UUID.randomUUID());
        FlightAlertResponse none = new FlightAlertResponse(UUID.randomUUID(), "PAR", "DKR", null, null, null, null,
                null, null, true, new BigDecimal("200.00"), "EUR", false, new BigDecimal("327"),
                LocalDateTime.now(), LocalDateTime.now(), 0, List.of());
        when(alertService.list(UID)).thenReturn(List.of(checked, none));

        mockMvc.perform(get("/me/flight-alerts").with(authentication(member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].matchCount").value(4))
                .andExpect(jsonPath("$[0].matchAirlineCodes.length()").value(2))
                .andExpect(jsonPath("$[0].matchAirlineCodes[1]").value("SS"))
                .andExpect(jsonPath("$[1].matchCount").value(0))
                .andExpect(jsonPath("$[1].matchAirlineCodes").isEmpty());
    }

    @Test
    void alertOffers_owner_returnsOffersUnderThreshold() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertOffersService.offers(UID, id, FlightAlertOffersScope.BELOW)).thenReturn(new FlightAlertOffersResponse(id, "PAR", "ABJ",
                "Paris", "Abidjan", true, "EUR", new BigDecimal("400.00"), List.of(
                        new com.yadony.api.flights.dto.FlightOfferDto("PAR", "ABJ", "CDG", "ABJ",
                                "2026-12-04T11:30:00+01:00", "2026-12-20T23:00:00Z", new BigDecimal("327"), "EUR",
                                "AF", "Air France", "702", 0, 0, 750, "Air France", "/search/X?t=1", null,
                                375, 375, null, null)),
                "/search/PAR0412ABJ20121", java.time.Instant.parse("2026-10-15T10:00:00Z"), 1));

        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers").with(authentication(member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertId").value(id.toString()))
                .andExpect(jsonPath("$.originCode").value("PAR"))
                .andExpect(jsonPath("$.destinationLabel").value("Abidjan"))
                .andExpect(jsonPath("$.roundTrip").value(true))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.maxPrice").value(400.00))
                .andExpect(jsonPath("$.offers[0].price").value(327))
                .andExpect(jsonPath("$.offers[0].airlineCode").value("AF"))
                .andExpect(jsonPath("$.searchPath").value("/search/PAR0412ABJ20121"))
                .andExpect(jsonPath("$.checkedAt").value("2026-10-15T10:00:00Z"))
                .andExpect(jsonPath("$.belowCount").value(1));
    }

    @Test
    void alertOffers_scopeAll_passesAllToService() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertOffersService.offers(UID, id, FlightAlertOffersScope.ALL)).thenReturn(new FlightAlertOffersResponse(
                id, "PAR", "ABJ", null, null, false, "EUR", new BigDecimal("200.00"), List.of(
                        new com.yadony.api.flights.dto.FlightOfferDto("PAR", "ABJ", "CDG", "ABJ",
                                "2026-12-04T11:30:00+01:00", null, new BigDecimal("327"), "EUR",
                                "AF", "Air France", "702", 0, 0, 750, "Air France", "/search/X?t=1", null,
                                375, 375, null, null)),
                null, java.time.Instant.parse("2026-10-15T10:00:00Z"), 0));

        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers").param("scope", "all")
                        .with(authentication(member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[0].price").value(327))
                .andExpect(jsonPath("$.belowCount").value(0));
        verify(alertOffersService).offers(UID, id, FlightAlertOffersScope.ALL);
    }

    @Test
    void alertOffers_scopeBelowExplicit_passesBelowToService() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertOffersService.offers(UID, id, FlightAlertOffersScope.BELOW)).thenReturn(new FlightAlertOffersResponse(
                id, "PAR", "ABJ", null, null, false, "EUR", new BigDecimal("400.00"), List.of(), null,
                java.time.Instant.parse("2026-10-15T10:00:00Z"), 0));

        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers").param("scope", "below")
                        .with(authentication(member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.belowCount").value(0));
    }

    @Test
    void alertOffers_unknownScope_is400ProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers").param("scope", "cheap")
                        .with(authentication(member())))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("flights-invalid-scope"));
        verifyNoInteractions(alertOffersService);
    }

    @Test
    void alertOffers_withoutSearchPath_omitsIt() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertOffersService.offers(UID, id, FlightAlertOffersScope.BELOW)).thenReturn(new FlightAlertOffersResponse(id, "PAR", "ABJ",
                null, null, false, "EUR", new BigDecimal("400.00"), List.of(), null,
                java.time.Instant.parse("2026-10-15T10:00:00Z"), 0));

        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers").with(authentication(member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offers").isEmpty())
                .andExpect(jsonPath("$.searchPath").doesNotExist())
                .andExpect(jsonPath("$.originLabel").doesNotExist());
    }

    @Test
    void alertOffers_otherUsersAlert_is404() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertOffersService.offers(UID, id, FlightAlertOffersScope.BELOW)).thenThrow(FlightErrors.alertNotFound());

        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers").with(authentication(member())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("flight-alert-not-found"));
    }

    @Test
    void alertOffers_providerDown_isProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertOffersService.offers(UID, id, FlightAlertOffersScope.BELOW)).thenThrow(FlightErrors.providerError());

        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers").with(authentication(member())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("flights-provider-error"));
    }

    @Test
    void alertOffers_withoutToken_is401_andGuest_is403() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/me/flight-alerts/" + id + "/offers").with(authentication(guest())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(alertOffersService);
    }

    @Test
    void createAlert_missingMaxPrice_isRejected() throws Exception {
        mockMvc.perform(post("/me/flight-alerts").with(authentication(member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originCode\":\"PAR\",\"destinationCode\":\"ABJ\"}"))
                .andExpect(status().is4xxClientError());
        verify(alertService, never()).create(any(), any());
    }

    @Test
    void createAlert_limitReached_is422() throws Exception {
        when(alertService.create(eq(UID), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "flight-alert-limit-reached", "Limit", "Limite atteinte"));

        mockMvc.perform(post("/me/flight-alerts").with(authentication(member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originCode\":\"PAR\",\"destinationCode\":\"ABJ\",\"maxPrice\":400}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("flight-alert-limit-reached"));
    }

    /** Décision produit : un visiteur cherche et réserve sans compte ; les alertes exigent un vrai compte. */
    @Test
    void guests_canSearchAndBook_butNotUseAlerts() throws Exception {
        when(searchService.search(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new FlightSearchResponse("PAR", "ABJ", false, "EUR", List.of(), null));
        when(dealService.deals(any(), any())).thenReturn(new FlightDealsResponse("EUR", false, List.of()));
        when(placeService.suggest(any(), any())).thenReturn(List.of());
        when(defaultsService.defaults(any())).thenReturn(new FlightDefaultsDto("none", null, null));
        when(clickService.click(any(), any())).thenReturn(new FlightClickResponse("https://x.test/search/X?marker=1"));
        when(users.resolve(any())).thenReturn(new FlightUsers.Caller(null, "fr"));

        mockMvc.perform(get("/flights/search").with(authentication(guest()))
                        .param("origin", "PAR").param("destination", "ABJ"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/flights/deals").with(authentication(guest()))).andExpect(status().isOk());
        mockMvc.perform(get("/flights/places").with(authentication(guest())).param("term", "abi"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/flights/defaults").with(authentication(guest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("none"));
        mockMvc.perform(post("/flights/clicks").with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerPath\":\"/search/X\",\"originCode\":\"PAR\","
                                + "\"destinationCode\":\"ABJ\",\"source\":\"deal\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/me/flight-alerts").with(authentication(guest())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/me/flight-alerts").with(authentication(guest()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(alertService);
    }

    @Test
    void defaults_returnsPrefill() throws Exception {
        when(defaultsService.defaults(UID)).thenReturn(new FlightDefaultsDto("search",
                new FlightPlaceDto("PAR", "Paris", "FR", "France"),
                new FlightPlaceDto("ABJ", "Abidjan", "CI", "Côte d'Ivoire")));

        mockMvc.perform(get("/flights/defaults").with(authentication(memberWithoutRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("search"))
                .andExpect(jsonPath("$.origin.code").value("PAR"))
                .andExpect(jsonPath("$.destination.name").value("Abidjan"));
    }

    @Test
    void anonymous_isRejected() throws Exception {
        mockMvc.perform(get("/flights/deals"))
                .andExpect(status().is4xxClientError());
    }

    /** Flag éteint : toutes les routes répondent 503, aucun service n'est appelé. */
    @Test
    void featureFlagOff_everyRouteIs503() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE, "flights-unavailable",
                "Flights Unavailable", "off")).when(feature).requireEnabled();

        mockMvc.perform(get("/flights/search").with(authentication(member()))
                        .param("origin", "PAR").param("destination", "ABJ"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/flights/deals").with(authentication(member())))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/me/flight-alerts").with(authentication(member())))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/flights/defaults").with(authentication(guest())))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(searchService, dealService, alertService, defaultsService);
    }
}
