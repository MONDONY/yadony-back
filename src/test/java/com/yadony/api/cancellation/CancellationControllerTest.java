package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yadony.api.cancellation.CancellationEntity;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CancellationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean CancellationService cancellationService;
    @MockBean UserRepository userRepository;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID BID_ID  = UUID.randomUUID();

    @BeforeEach
    void stubUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-test");
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByFirebaseUid("uid-test")).thenReturn(Optional.of(user));
    }

    private UsernamePasswordAuthenticationToken asRole(String role) {
        return new UsernamePasswordAuthenticationToken(
                "uid-test", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void reportNoShow_travelerRole_returns200() throws Exception {
        when(cancellationService.reportSenderNoShow(any(), any()))
                .thenReturn(new CancellationEntity());

        mockMvc.perform(post("/cancellations/bids/{bidId}/report-noshow", BID_ID)
                .with(authentication(asRole("TRAVELER"))))
                .andExpect(status().isOk());
    }

    @Test
    void reportNoShow_senderRole_returns403() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-noshow", BID_ID)
                .with(authentication(asRole("SENDER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void contestNoShow_senderRole_returns200() throws Exception {
        doNothing().when(cancellationService).contestSenderNoShow(any(), any());

        mockMvc.perform(post("/cancellations/bids/{bidId}/contest-noshow", BID_ID)
                .with(authentication(asRole("SENDER"))))
                .andExpect(status().isOk());
    }

    @Test
    void contestNoShow_travelerRole_returns403() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/contest-noshow", BID_ID)
                .with(authentication(asRole("TRAVELER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportNoShow_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/cancellations/bids/{bidId}/report-noshow", BID_ID))
                .andExpect(status().isUnauthorized());
    }
    /** FLUTTER-F9 : l'annulation du trajet refusée pendant le paiement sort en problem+json. */
    @Test
    void cancelTrip_offerAwaitingPayment_returns409ProblemJson() throws Exception {
        when(cancellationService.cancelTrip(any(), any())).thenThrow(new com.yadony.api.common.YadonyBusinessException(
                org.springframework.http.HttpStatus.CONFLICT, "offer-accepted-awaiting-payment",
                "Offer Accepted Awaiting Payment", "Paiement en cours",
                java.util.Map.of("negotiationStatus", "AWAITING_PAYMENT")));

        mockMvc.perform(post("/cancellations")
                .with(authentication(asRole("TRAVELER")))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"announcementId\":\"" + UUID.randomUUID() + "\",\"reason\":\"Imprévu\"}"))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("offer-accepted-awaiting-payment"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.negotiationStatus").value("AWAITING_PAYMENT"));
    }
}
