package com.yadony.api.kyc;

import com.yadony.api.common.stripe.StripeWebhookIngestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KycControllerAbandonReasonTest {

    private KycService kycService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        kycService = mock(KycService.class);
        mvc = MockMvcBuilders.standaloneSetup(new KycController(kycService, mock(StripeWebhookIngestService.class)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid-1", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void transmet_la_raison_et_repond_204() throws Exception {
        mvc.perform(post("/kyc/abandon-reason").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"TOO_LONG\"}"))
                .andExpect(status().isNoContent());
        verify(kycService).recordAbandonReason("uid-1", "TOO_LONG");
    }

    @Test
    void refuse_une_raison_vide() throws Exception {
        mvc.perform(post("/kyc/abandon-reason").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(kycService, never()).recordAbandonReason(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
