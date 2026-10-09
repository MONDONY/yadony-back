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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
    @MockitoBean private BidCheckoutService bidCheckoutService;

    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID COUNTERPARTY_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken authenticatedAs(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority(role)));
    }

    private static BidNegotiationResponse response(String status) {
        return new BidNegotiationResponse(
                // Vue EXPÉDITEUR : commissionEur renseigné, netEur tu.
                BID_ID, ANNOUNCEMENT_ID, status, "SENDER", 1, 3, true, true, "EUR",
                new BigDecimal("45.00"), null, new BigDecimal("2.14"), new BigDecimal("26.25"),
                new BigDecimal("5.0"), "Vêtements", "CLOTHING",
                List.of(), List.of(), List.of(), "Moussa D.", "Paris", "Dakar",
                LocalDate.now().plusDays(10), LocalDateTime.now().plusHours(72), List.of(), "CASH",
                COUNTERPARTY_ID, null);
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
        when(negotiationService.reject(eq(BID_ID), anyString()))
                .thenReturn(response("NEGOTIATION_CLOSED"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/reject")
                        .with(authentication(authenticatedAs("uid-traveler", "ROLE_TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEGOTIATION_CLOSED"));
    }

    @Test
    @DisplayName("POST cancel → 200")
    void cancel_returns200() throws Exception {
        when(negotiationService.cancel(eq(BID_ID), anyString()))
                .thenReturn(response("NEGOTIATION_CLOSED"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/cancel")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEGOTIATION_CLOSED"));
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
                .andExpect(jsonPath("$.announcementId").value(ANNOUNCEMENT_ID.toString()))
                .andExpect(jsonPath("$.counterpartyId").value(COUNTERPARTY_ID.toString()));
    }

    @Test
    @DisplayName("GET /bids/negotiations/me → 200")
    void myNegotiations_returns200() throws Exception {
        when(negotiationService.myNegotiations(anyString(), eq(false))).thenReturn(List.of(
                new BidNegotiationSummaryResponse(BID_ID, ANNOUNCEMENT_ID, "NEGOTIATING", 1,
                        true, true, new BigDecimal("45.00"), "EUR", "Moussa D.", "Paris", "Dakar",
                        LocalDate.now().plusDays(10), LocalDateTime.now(), "SENDER")));

        mockMvc.perform(get("/bids/negotiations/me")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$[0].role").value("SENDER"));
    }

    // ── rangement / retrait d'une discussion (FLUTTER-EJ) ───────────────────────

    @Test
    @DisplayName("GET /bids/negotiations/me?archived=true → filtre « Archivées », champ archived")
    void myNegotiations_archivedFilter() throws Exception {
        when(negotiationService.myNegotiations(anyString(), eq(true))).thenReturn(List.of(
                new BidNegotiationSummaryResponse(BID_ID, ANNOUNCEMENT_ID, "NEGOTIATION_CLOSED", 2,
                        false, false, new BigDecimal("45.00"), "EUR", "Moussa D.", "Paris", "Dakar",
                        LocalDate.now().plusDays(10), LocalDateTime.now(), "SENDER", true)));

        mockMvc.perform(get("/bids/negotiations/me").param("archived", "true")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].archived").value(true));
    }

    @Test
    @DisplayName("GET /bids/negotiations/me sans filtre → archived=false par ligne")
    void myNegotiations_defaultsToNotArchived() throws Exception {
        when(negotiationService.myNegotiations(anyString(), eq(false))).thenReturn(List.of(
                new BidNegotiationSummaryResponse(BID_ID, ANNOUNCEMENT_ID, "NEGOTIATING", 1,
                        true, true, new BigDecimal("45.00"), "EUR", "Moussa D.", "Paris", "Dakar",
                        LocalDate.now().plusDays(10), LocalDateTime.now(), "TRAVELER")));

        mockMvc.perform(get("/bids/negotiations/me")
                        .with(authentication(authenticatedAs("uid-traveler", "ROLE_TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].archived").value(false));
    }

    @Test
    @DisplayName("POST /bids/{id}/negotiation/archive → 204")
    void archive_returns204() throws Exception {
        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/archive")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isNoContent());
        verify(negotiationService).archive(BID_ID, "uid-sender");
    }

    @Test
    @DisplayName("POST /bids/{id}/negotiation/unarchive → 204")
    void unarchive_returns204() throws Exception {
        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/unarchive")
                        .with(authentication(authenticatedAs("uid-traveler", "ROLE_TRAVELER"))))
                .andExpect(status().isNoContent());
        verify(negotiationService).unarchive(BID_ID, "uid-traveler");
    }

    @Test
    @DisplayName("DELETE /bids/{id}/negotiation → 204 (retrait de la liste de l'appelant)")
    void hide_returns204() throws Exception {
        mockMvc.perform(delete("/bids/" + BID_ID + "/negotiation")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isNoContent());
        verify(negotiationService).hide(BID_ID, "uid-sender");
    }

    @Test
    @DisplayName("archiver une discussion en cours → 409 problem+json negotiation-still-open")
    void archive_openNegotiation_is409() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "negotiation-still-open",
                "Negotiation Still Open", "Seule une discussion de prix terminée peut être archivée ou supprimée.",
                java.util.Map.of("negotiationStatus", "NEGOTIATING")))
                .when(negotiationService).archive(eq(BID_ID), anyString());

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/archive")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("negotiation-still-open"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.negotiationStatus").value("NEGOTIATING"))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("negotiation-still-open")));
    }

    @Test
    @DisplayName("supprimer la discussion d'un autre → 403")
    void hide_nonParticipant_is403() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "Vous ne participez pas à cette discussion"))
                .when(negotiationService).hide(eq(BID_ID), anyString());

        mockMvc.perform(delete("/bids/" + BID_ID + "/negotiation")
                        .with(authentication(authenticatedAs("uid-third", "ROLE_SENDER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    @DisplayName("archiver exige une authentification")
    void archive_withoutAuth_is4xx() throws Exception {
        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/archive"))
                .andExpect(status().is4xxClientError());
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
    @DisplayName("contre-offre après la date limite de dépôt → 409 problem+json handover-deadline-passed (FLUTTER-GA)")
    void counter_afterHandoverDeadline_is409() throws Exception {
        when(negotiationService.counter(eq(BID_ID), anyString(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "handover-deadline-passed",
                        "Handover Deadline Passed", "La date limite de remise des colis pour ce trajet est passée"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/counter")
                        .with(authentication(authenticatedAs("uid-traveler", "ROLE_TRAVELER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BidNegotiationCounterRequest(new BigDecimal("40.00"), "ok"))))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("handover-deadline-passed"));
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

    // ── checkout d'un accord carte ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /bids/{id}/negotiation/checkout → 200 avec la forme d'un BidCheckoutResponse")
    void negotiationCheckout_returnsCheckoutPayload() throws Exception {
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(24).withNano(0);
        when(bidCheckoutService.negotiationCheckout(anyString(), eq(BID_ID)))
                .thenReturn(new com.yadony.api.matching.dto.BidCheckoutResponse(
                        BID_ID, "pi_secret_xyz", "pk_test_123", expiresAt,
                        "eur", List.of("card", "paypal")));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/checkout")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$.clientSecret").value("pi_secret_xyz"))
                .andExpect(jsonPath("$.publishableKey").value("pk_test_123"))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.currency").value("eur"))
                .andExpect(jsonPath("$.paymentMethodTypes[0]").value("card"));
    }

    @Test
    @DisplayName("le checkout exige une authentification")
    void negotiationCheckout_withoutAuth_is4xx() throws Exception {
        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/checkout"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("un accord en espèces n'a pas de checkout → 409 bid-not-awaiting-payment")
    void negotiationCheckout_onCashAgreement_isRfc7807Conflict() throws Exception {
        when(bidCheckoutService.negotiationCheckout(anyString(), eq(BID_ID)))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                        "bid-not-awaiting-payment", "Bid Not Awaiting Payment",
                        "Cette demande n'attend pas de paiement"));

        mockMvc.perform(post("/bids/" + BID_ID + "/negotiation/checkout")
                        .with(authentication(authenticatedAs("uid-sender", "ROLE_SENDER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("bid-not-awaiting-payment"));
    }
}
