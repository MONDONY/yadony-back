package com.yadony.api.cancellation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CancellationControllerDeliveryNoShowTest {

    @Autowired MockMvc mockMvc;
    @MockBean CancellationService cancellationService;
    @MockBean com.yadony.api.auth.UserRepository userRepository;
    @MockBean NoShowArbitrationService arbitrationService;
    @MockBean DeliveryNoShowProcedureService procedureService;

    static final UUID BID_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken asRole(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private void stubUser(String uid) {
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByFirebaseUid(uid)).thenReturn(java.util.Optional.of(user));
    }

    @Test
    void reportDeliveryNoShow_okForTraveler() throws Exception {
        stubUser("uid-traveler");
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isOk());
        verify(cancellationService).reportDeliveryNoShow(eq(BID_ID), any(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void reportDeliveryNoShow_forbiddenForSender() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportTravelerDeliveryNoShow_okForSender() throws Exception {
        stubUser("uid-sender");
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-traveler-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isOk());
        verify(cancellationService).reportTravelerDeliveryNoShow(eq(BID_ID), any());
    }

    @Test
    void reportTravelerDeliveryNoShow_forbiddenForTraveler() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-traveler-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void contestDeliveryNoShow_okForSender() throws Exception {
        stubUser("uid-sender");
        mockMvc.perform(post("/cancellations/bids/{bidId}/contest-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isOk());
    }

    @Test
    void contestDeliveryNoShow_okForTraveler() throws Exception {
        stubUser("uid-traveler");
        mockMvc.perform(post("/cancellations/bids/{bidId}/contest-delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isOk());
    }

    private static UUID eq(UUID v) { return org.mockito.ArgumentMatchers.eq(v); }
    private static UUID any() { return org.mockito.ArgumentMatchers.any(); }

    // ── confirm-noshow (admin, historique) : délègue à l'arbitrage admin, l'admin
    // authentifié y est propagé pour l'audit ──

    @Test
    void confirmNoShow_admin_propagatesAdminId() throws Exception {
        UUID adminId = UUID.randomUUID();
        var principal = new com.yadony.api.admin.account.AdminPrincipal(
                adminId, "admin@yadony.test", com.yadony.api.admin.account.AdminRole.ADMIN, false, "uid-admin");
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("DISPUTE_RESOLVE")));

        mockMvc.perform(post("/cancellations/bids/{bidId}/confirm-noshow", BID_ID)
                        .with(authentication(auth)))
                .andExpect(status().isOk());

        verify(arbitrationService).confirmLegacyByBid(BID_ID, adminId);
        verify(cancellationService, never()).confirmSenderNoShow(any());
    }

    @Test
    void confirmNoShow_adminWithoutAdminPrincipal_returns403() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("uid-x", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("DISPUTE_RESOLVE")));

        mockMvc.perform(post("/cancellations/bids/{bidId}/confirm-noshow", BID_ID)
                        .with(authentication(auth)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(cancellationService, arbitrationService);
    }

    // ── Procédure « destinataire absent » (FLUTTER-E2) ──

    @Test
    void reportDeliveryNoShow_transmetLaConfirmation() throws Exception {
        stubUser("uid-traveler");
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-delivery-noshow", BID_ID)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"contactConfirmed\":true}")
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isOk());
        verify(cancellationService).reportDeliveryNoShow(eq(BID_ID), any(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void reportDeliveryNoShow_procedureRefusee_problemJson() throws Exception {
        stubUser("uid-traveler");
        when(cancellationService.reportDeliveryNoShow(eq(BID_ID), any(), anyBoolean()))
                .thenThrow(new com.yadony.api.common.YadonyBusinessException(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "delivery-noshow-wait-not-elapsed", "Waiting Time Not Elapsed", "Attendez",
                        java.util.Map.of("availableAt", "2026-10-08T12:00Z")));
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-delivery-noshow", BID_ID)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"contactConfirmed\":true}")
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("delivery-noshow-wait-not-elapsed"))
                .andExpect(jsonPath("$.availableAt").value("2026-10-08T12:00Z"));
    }

    @Test
    void getProcedure_okPourLesDeuxParties() throws Exception {
        stubUser("uid-sender");
        when(procedureService.getProcedure(eq(BID_ID), any())).thenReturn(
                new com.yadony.api.cancellation.dto.DeliveryNoShowProcedureResponse(BID_ID, "SENDER", "ARRIVED",
                        null, null, false, null, false, true, "CONFIRMED", null, null, null, null, null,
                        true, 120, 7));
        mockMvc.perform(get("/cancellations/bids/{bidId}/delivery-noshow", BID_ID)
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SENDER"))
                .andExpect(jsonPath("$.canSetRetryAppointment").value(true))
                .andExpect(jsonPath("$.holdDays").value(7));
    }

    @Test
    void retryAppointment_okPourLExpediteur() throws Exception {
        stubUser("uid-sender");
        mockMvc.perform(post("/cancellations/bids/{bidId}/delivery-noshow/retry-appointment", BID_ID)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"appointmentAt\":\"2030-01-01T10:00:00Z\",\"note\":\"Gare\"}")
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(status().isOk());
        verify(procedureService).setRetryAppointment(eq(BID_ID), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("Gare"));
    }

    @Test
    void retryAppointment_interditAuVoyageur() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/delivery-noshow/retry-appointment", BID_ID)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"appointmentAt\":\"2030-01-01T10:00:00Z\"}")
                        .with(authentication(asRole("uid-traveler", "TRAVELER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(procedureService);
    }

    @Test
    void retryAppointment_dateManquante_refusee() throws Exception {
        stubUser("uid-sender");
        mockMvc.perform(post("/cancellations/bids/{bidId}/delivery-noshow/retry-appointment", BID_ID)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(authentication(asRole("uid-sender", "SENDER"))))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                        .isBetween(400, 422));
        verifyNoInteractions(procedureService);
    }
}
