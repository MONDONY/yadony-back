package com.yadony.api.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.dto.BidNegotiationCounterRequest;
import com.yadony.api.matching.dto.BidNegotiationResponse;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.matching.dto.BidNegotiationSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("BidNegotiationController — endpoints du fil de négociation")
class BidNegotiationControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private BidNegotiationService negotiationService;

    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken authenticatedAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private static BidNegotiationResponse response(String status) {
        return new BidNegotiationResponse(
                BID_ID, ANNOUNCEMENT_ID, status, 1, 3, true, true, "EUR",
                new BigDecimal("45.00"), null, new BigDecimal("2.14"), new BigDecimal("26.25"),
                new BigDecimal("5.0"), "Vêtements", "CLOTHING",
                List.of(), List.of(), List.of(), "Moussa D.", "Paris", "Dakar",
                LocalDate.now().plusDays(10), LocalDateTime.now().plusHours(72), List.of());
    }

    private static BidNegotiationStartRequest startRequest(BigDecimal proposed) {
        return new BidNegotiationStartRequest(
                new BigDecimal("5.0"), "Vêtements", "CLOTHING",
                "Fatou Sarr", "+221701234567", true,
                "CASH", null, null, null, proposed, null, null);
    }

    @Test
    @DisplayName("POST /announcements/{id}/bids/negotiation → 201")
    void propose_returns201() throws Exception {
        when(negotiationService.propose(eq(ANNOUNCEMENT_ID), anyString(), any(), any()))
                .thenReturn(response("NEGOTIATING"));

        mockMvc.perform(post("/announcements/" + ANNOUNCEMENT_ID + "/bids/negotiation")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest(new BigDecimal("45.00")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$.status").value("NEGOTIATING"));
    }

    @Test
    @DisplayName("un corps invalide est refusé en 4xx")
    void propose_withInvalidBody_returns4xx() throws Exception {
        mockMvc.perform(post("/announcements/" + ANNOUNCEMENT_ID + "/bids/negotiation")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest(null))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("sans authentification, l'appel est refusé")
    void propose_unauthenticated_isRejected() throws Exception {
        mockMvc.perform(post("/announcements/" + ANNOUNCEMENT_ID + "/bids/negotiation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest(new BigDecimal("45.00")))))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST counter → 200")
    void counter_returns200() throws Exception {
        when(negotiationService.counter(eq(BID_ID), anyString(), any()))
                .thenReturn(response("NEGOTIATING"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/counter")
                        .with(authentication(authenticatedAs("uid-traveler", "ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BidNegotiationCounterRequest(new BigDecimal("40.00"), "ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round").value(1));
    }

    @Test
    @DisplayName("POST accept → 200")
    void accept_returns200() throws Exception {
        when(negotiationService.accept(eq(BID_ID), anyString()))
                .thenReturn(response("AWAITING_PAYMENT"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/accept")
                        .with(authentication(authenticatedAs("uid-traveler", "ROLE_TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"));
    }

    @Test
    @DisplayName("POST reject → 200")
    void reject_returns200() throws Exception {
        when(negotiationService.reject(eq(BID_ID), anyString())).thenReturn(response("REJECTED"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/reject")
                        .with(authentication(authenticatedAs("uid-traveler", "ROLE_TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    @DisplayName("POST cancel → 200")
    void cancel_returns200() throws Exception {
        when(negotiationService.cancel(eq(BID_ID), anyString())).thenReturn(response("CANCELLED"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/cancel")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("POST read → 204")
    void markRead_returns204() throws Exception {
        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/read")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isNoContent());

        verify(negotiationService).markRead(eq(BID_ID), anyString());
    }

    @Test
    @DisplayName("GET du fil → 200")
    void thread_returns200() throws Exception {
        when(negotiationService.thread(eq(BID_ID), anyString())).thenReturn(response("NEGOTIATING"));

        mockMvc.perform(get("/bids/" + BID_ID + "/negotiation")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcementId").value(ANNOUNCEMENT_ID.toString()));
    }

    @Test
    @DisplayName("GET /bids/negotiations/me → 200")
    void myNegotiations_returns200() throws Exception {
        when(negotiationService.myNegotiations(anyString())).thenReturn(List.of(
                new BidNegotiationSummaryResponse(BID_ID, ANNOUNCEMENT_ID, "NEGOTIATING", 1,
                        true, true, new BigDecimal("45.00"), "EUR", "Moussa D.", "Paris", "Dakar",
                        LocalDate.now().plusDays(10), LocalDateTime.now(), "SENDER")));

        mockMvc.perform(get("/bids/negotiations/me")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$[0].role").value("SENDER"));
    }

    @Test
    @DisplayName("un 409 métier ressort en problem+json avec sa propriété code")
    void businessConflict_isRfc7807() throws Exception {
        when(negotiationService.accept(eq(BID_ID), anyString()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "not-your-turn",
                        "Not Your Turn", "C'est à la contrepartie de répondre"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/accept")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not-your-turn"));
    }

    @Test
    @DisplayName("un 422 métier ressort en problem+json avec sa propriété code")
    void businessUnprocessable_isRfc7807() throws Exception {
        when(negotiationService.propose(eq(ANNOUNCEMENT_ID), anyString(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "announcement-not-negotiable", "Announcement Not Negotiable",
                        "Ce trajet n'accepte pas les propositions de prix"));

        mockMvc.perform(post("/announcements/" + ANNOUNCEMENT_ID + "/bids/negotiation")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(startRequest(new BigDecimal("45.00")))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("announcement-not-negotiable"));
    }
}
