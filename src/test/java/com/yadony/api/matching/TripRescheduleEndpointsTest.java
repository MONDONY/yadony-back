package com.yadony.api.matching;

import com.yadony.api.cancellation.RescheduleDecision;
import com.yadony.api.cancellation.RescheduleDecisionService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.TripRescheduleResponse;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Report de trajet (voyageur) et réponse de l'expéditeur, de bout en bout côté HTTP. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TripRescheduleEndpointsTest {

    @Autowired MockMvc mockMvc;
    @MockBean TripRescheduleService rescheduleService;
    @MockBean RescheduleDecisionService decisionService;

    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken user() {
        return new UsernamePasswordAuthenticationToken("uid-test", null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER"), new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static final String BODY = """
            {"departureDate":"2030-03-14","departureTime":"22:30","arrivalDate":"2030-03-15",
             "arrivalTime":"06:30","handoverDeadline":"2030-03-13T18:00:00","reason":"FLIGHT_CANCELLED",
             "note":"Vol annulé"}
            """;

    @Test
    void reschedule_returnsTheSummary() throws Exception {
        UUID rescheduleId = UUID.randomUUID();
        when(rescheduleService.reschedule(eq(TRIP_ID), eq("uid-test"), any()))
                .thenReturn(new TripRescheduleResponse(rescheduleId, 1, 1, 2, 1));

        mockMvc.perform(post("/announcements/{id}/reschedule", TRIP_ID).with(authentication(user()))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rescheduleId").value(rescheduleId.toString()))
                .andExpect(jsonPath("$.remainingReschedules").value(1))
                .andExpect(jsonPath("$.parcelsAwaitingDecision").value(2));
    }

    @Test
    void reschedule_withoutReason_is422() throws Exception {
        mockMvc.perform(post("/announcements/{id}/reschedule", TRIP_ID).with(authentication(user()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departureDate\":\"2030-03-14\",\"departureTime\":\"22:30\"}"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(rescheduleService);
    }

    @Test
    void reschedule_limitReached_isAProblemDetail() throws Exception {
        when(rescheduleService.reschedule(eq(TRIP_ID), eq("uid-test"), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "reschedule-limit-reached",
                        "Reschedule Limit Reached", "Ce trajet a déjà été reporté 2 fois."));

        mockMvc.perform(post("/announcements/{id}/reschedule", TRIP_ID).with(authentication(user()))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void reschedule_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(post("/announcements/{id}/reschedule", TRIP_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(rescheduleService);
    }

    @Test
    void decision_withdraw_returns204() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/reschedule-decision", BID_ID).with(authentication(user()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"WITHDRAW\"}"))
                .andExpect(status().isNoContent());

        verify(decisionService).decide("uid-test", BID_ID, RescheduleDecision.WITHDRAW);
    }

    @Test
    void decision_closed_is409() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "reschedule-decision-closed", "Decision Closed",
                "Le délai pour répondre au report est dépassé."))
                .when(decisionService).decide("uid-test", BID_ID, RescheduleDecision.KEEP);

        mockMvc.perform(post("/cancellations/bids/{bidId}/reschedule-decision", BID_ID).with(authentication(user()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"KEEP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").exists());
    }
}
