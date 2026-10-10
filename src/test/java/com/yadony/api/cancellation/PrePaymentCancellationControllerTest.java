package com.yadony.api.cancellation;

import com.yadony.api.cancellation.dto.PrePaymentCancellationResponse;
import com.yadony.api.common.YadonyBusinessException;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PrePaymentCancellationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean PrePaymentCancellationService service;

    private static final UUID BID_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken sender() {
        return new UsernamePasswordAuthenticationToken("uid-sender", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void cancel_returnsTheCancelledBid() throws Exception {
        when(service.cancel(eq("uid-sender"), eq(BID_ID)))
                .thenReturn(new PrePaymentCancellationResponse(BID_ID, "CANCELLED", false));

        mockMvc.perform(post("/bids/{id}/cancel-before-payment", BID_ID).with(authentication(sender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.alreadyCancelled").value(false));
    }

    @Test
    void paymentInProgress_problemJson409() throws Exception {
        when(service.cancel(eq("uid-sender"), eq(BID_ID))).thenThrow(new YadonyBusinessException(
                HttpStatus.CONFLICT, "payment-in-progress", "Payment In Progress", "Un paiement est en cours"));

        mockMvc.perform(post("/bids/{id}/cancel-before-payment", BID_ID).with(authentication(sender())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("payment-in-progress"));
    }

    @Test
    void unauthenticated_401() throws Exception {
        mockMvc.perform(post("/bids/{id}/cancel-before-payment", BID_ID))
                .andExpect(status().isUnauthorized());
    }
}
