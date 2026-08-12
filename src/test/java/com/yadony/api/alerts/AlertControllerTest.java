package com.yadony.api.alerts;

import com.yadony.api.alerts.AlertDirection;
import com.yadony.api.alerts.dto.AlertTripMatchDto;
import com.yadony.api.alerts.dto.CorridorAlertRequest;
import com.yadony.api.alerts.dto.CorridorAlertResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.MatchingRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AlertControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AlertService alertService;

    private static final String FIREBASE_UID = "uid-alert-test";

    private UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UsernamePasswordAuthenticationToken asSender() {
        return new UsernamePasswordAuthenticationToken(
                FIREBASE_UID, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private CorridorAlertResponse sampleResponse() {
        return new CorridorAlertResponse(UUID.randomUUID(), "Paris", "Bamako", "FR", "ML",
                null, null, null, List.of(), AlertDirection.TRAVELER_WANTS_PACKAGES, true, 3L, java.time.LocalDateTime.now());
    }

    @Test
    void list_asTraveler_returns200() throws Exception {
        when(alertService.list(eq(FIREBASE_UID), isNull())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/me/corridor-alerts").with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].departureCity").value("Paris"))
                .andExpect(jsonPath("$[0].matchCount").value(3));
    }

    @Test
    void list_asSender_returns200() throws Exception {
        when(alertService.list(eq(FIREBASE_UID), isNull())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/me/corridor-alerts").with(authentication(asSender())))
                .andExpect(status().isOk());
    }

    @Test
    void list_withDirectionFilter_passesEnum() throws Exception {
        // stub direction is mocked; routing is the assertion
        when(alertService.list(eq(FIREBASE_UID), eq(AlertDirection.SENDER_WANTS_TRIPS)))
                .thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/me/corridor-alerts")
                        .param("direction", "SENDER_WANTS_TRIPS")
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].departureCity").value("Paris"));

        verify(alertService).list(FIREBASE_UID, AlertDirection.SENDER_WANTS_TRIPS);
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/me/corridor-alerts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_asTraveler_returns201() throws Exception {
        when(alertService.create(eq(FIREBASE_UID), any(CorridorAlertRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako",
                                "direction", "TRAVELER_WANTS_PACKAGES"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.departureCity").value("Paris"));
    }

    @Test
    void create_asSender_tripDirection_returns201() throws Exception {
        CorridorAlertResponse tripResp = new CorridorAlertResponse(UUID.randomUUID(), "Paris", "Dakar", "FR", "SN",
                null, null, null, List.of(), AlertDirection.SENDER_WANTS_TRIPS, true, 0L, java.time.LocalDateTime.now());
        when(alertService.create(eq(FIREBASE_UID), any(CorridorAlertRequest.class)))
                .thenReturn(tripResp);

        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asSender()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Dakar",
                                "direction", "SENDER_WANTS_TRIPS"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("SENDER_WANTS_TRIPS"));
    }

    @Test
    void create_directionNotAllowed_returns403() throws Exception {
        when(alertService.create(eq(FIREBASE_UID), any(CorridorAlertRequest.class)))
                .thenThrow(new YadonyBusinessException(HttpStatus.FORBIDDEN,
                        "alert-direction-not-allowed", "Alert Direction Not Allowed",
                        "Votre rôle ne permet pas de créer ce type d'alerte."));

        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asSender()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Dakar",
                                "direction", "TRAVELER_WANTS_PACKAGES"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("alert-direction-not-allowed"));
    }

    @Test
    void create_blankCity_returns422() throws Exception {
        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "", "arrivalCity", "Bamako"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void create_overCap_returns422() throws Exception {
        when(alertService.create(eq(FIREBASE_UID), any(CorridorAlertRequest.class)))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "alert-limit-reached", "Alert Limit Reached", "Limite atteinte."));

        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako",
                                "direction", "TRAVELER_WANTS_PACKAGES"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("alert-limit-reached"));
    }

    @Test
    void create_duplicate_returns409() throws Exception {
        when(alertService.create(eq(FIREBASE_UID), any(CorridorAlertRequest.class)))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                        "alert-duplicate", "Duplicate Alert", "Doublon."));

        mockMvc.perform(post("/me/corridor-alerts")
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako",
                                "direction", "TRAVELER_WANTS_PACKAGES"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("alert-duplicate"));
    }

    @Test
    void update_asTraveler_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertService.update(eq(FIREBASE_UID), eq(id), any(CorridorAlertRequest.class), eq(false)))
                .thenReturn(sampleResponse());

        mockMvc.perform(put("/me/corridor-alerts/{id}", id)
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako",
                                "direction", "TRAVELER_WANTS_PACKAGES", "active", false))))
                .andExpect(status().isOk());
    }

    @Test
    void update_notOwner_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(alertService.update(eq(FIREBASE_UID), eq(id), any(CorridorAlertRequest.class), any()))
                .thenThrow(new YadonyNotFoundException("Alert not found"));

        mockMvc.perform(put("/me/corridor-alerts/{id}", id)
                        .with(authentication(asTraveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureCity", "Paris", "arrivalCity", "Bamako",
                                "direction", "TRAVELER_WANTS_PACKAGES", "active", true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_notOwner_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new YadonyNotFoundException("Alert not found")).when(alertService).delete(FIREBASE_UID, id);

        mockMvc.perform(delete("/me/corridor-alerts/{id}", id)
                        .with(authentication(asTraveler())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void delete_asTraveler_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(alertService).delete(FIREBASE_UID, id);

        mockMvc.perform(delete("/me/corridor-alerts/{id}", id)
                        .with(authentication(asTraveler())))
                .andExpect(status().isNoContent());

        verify(alertService).delete(FIREBASE_UID, id);
    }

    @Test
    void matches_asTraveler_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        MatchingRequestDto dto = new MatchingRequestDto(
                UUID.randomUUID().toString(), null, "Paris → Bamako", "2026-07-10", 0.0,
                UUID.randomUUID().toString(), "Awa K", "AK", 4.5, 3,
                3.0, "Documents", 0.0, null, "excerpt", 0, "2026-06-20T10:00:00", "EUR");
        doReturn(List.of(dto)).when(alertService).getMatchesForDirection(FIREBASE_UID, id);

        mockMvc.perform(get("/me/corridor-alerts/{id}/matches", id)
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contentType").value("Documents"))
                .andExpect(jsonPath("$[0].tripCorridor").value("Paris → Bamako"));
    }

    @Test
    void matches_tripDirection_returnsTripDtos() throws Exception {
        UUID id = UUID.randomUUID();
        AlertTripMatchDto tripDto = new AlertTripMatchDto(
                UUID.randomUUID(), "Paris", "Dakar", LocalDate.of(2026, 8, 10),
                UUID.randomUUID(), "Mamadou D", "MD", 4.8,
                BigDecimal.valueOf(15), BigDecimal.valueOf(8), TransportMode.PLANE, null, "EUR");
        doReturn(List.of(tripDto)).when(alertService).getMatchesForDirection(FIREBASE_UID, id);

        mockMvc.perform(get("/me/corridor-alerts/{id}/matches", id)
                        .with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].availableKg").value(15))
                .andExpect(jsonPath("$[0].travelerName").value("Mamadou D"));
    }
}
