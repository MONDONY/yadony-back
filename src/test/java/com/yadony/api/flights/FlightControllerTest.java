package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
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
                new BigDecimal("400.00"), "EUR", true, null, null, LocalDateTime.now());
    }

    @Test
    void search_passesQueryToService() throws Exception {
        when(searchService.search(UID, "PAR", "ABJ", "2026-12", null, true, "XOF"))
                .thenReturn(new FlightSearchResponse("PAR", "ABJ", false, "XOF", List.of()));

        mockMvc.perform(get("/flights/search").with(authentication(memberWithoutRole()))
                        .param("origin", "PAR").param("destination", "ABJ")
                        .param("departureDate", "2026-12").param("direct", "true").param("currency", "XOF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.offers").isArray());
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

        mockMvc.perform(get("/me/flight-alerts").with(authentication(member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].destinationCode").value("ABJ"));

        mockMvc.perform(post("/me/flight-alerts").with(authentication(member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originCode\":\"PAR\",\"destinationCode\":\"ABJ\",\"maxPrice\":400}"))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/me/flight-alerts/" + alert.id()).with(authentication(member()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/me/flight-alerts/" + alert.id()).with(authentication(member())))
                .andExpect(status().isNoContent());
        verify(alertService).delete(UID, alert.id());
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
                .thenReturn(new FlightSearchResponse("PAR", "ABJ", false, "EUR", List.of()));
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
