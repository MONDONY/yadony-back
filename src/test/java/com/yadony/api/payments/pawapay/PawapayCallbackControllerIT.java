package com.yadony.api.payments.pawapay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PawapayCallbackControllerIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean PawapayOperationService operations;
    @MockitoBean PawapaySignatureVerifier verifier;

    @Test
    void depositCompleted_isPublic_andApplied() throws Exception {
        UUID id = UUID.randomUUID();
        when(operations.apply(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/pawapay/callbacks/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"depositId\":\"" + id + "\",\"status\":\"COMPLETED\",\"providerTransactionId\":\"ptx-1\"}"))
                .andExpect(status().isOk());

        verify(operations).apply(eq(id), eq(PawapayOperationStatus.COMPLETED), isNull(), isNull(), eq("ptx-1"),
                isNull(), any(), eq(PawapayOperationService.Source.CALLBACK));
    }

    @Test
    void depositProcessing_withAuthorizationUrl_isApplied() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/pawapay/callbacks/deposits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"depositId\":\"" + id + "\",\"status\":\"PROCESSING\",\"nextStep\":\"REDIRECT_TO_AUTH_URL\",\"authorizationUrl\":\"https://wave.test/auth\"}"))
                .andExpect(status().isOk());
        verify(operations).apply(eq(id), eq(PawapayOperationStatus.PROCESSING), isNull(), isNull(), isNull(),
                eq("https://wave.test/auth"), any(), eq(PawapayOperationService.Source.CALLBACK));
    }

    @Test
    void payoutFailed_carriesFailureReason() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/pawapay/callbacks/payouts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payoutId\":\"" + id + "\",\"status\":\"FAILED\",\"failureReason\":{\"failureCode\":\"RECIPIENT_NOT_FOUND\",\"failureMessage\":\"no wallet\"}}"))
                .andExpect(status().isOk());
        verify(operations).apply(eq(id), eq(PawapayOperationStatus.FAILED), eq("RECIPIENT_NOT_FOUND"), eq("no wallet"),
                isNull(), isNull(), any(), eq(PawapayOperationService.Source.CALLBACK));
    }

    @Test
    void unknownStatus_orMissingId_isAcknowledgedWithoutApply() throws Exception {
        mockMvc.perform(post("/pawapay/callbacks/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundId\":\"" + UUID.randomUUID() + "\",\"status\":\"SOMETHING_NEW\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/pawapay/callbacks/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/pawapay/callbacks/refunds").contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isOk());
        verify(operations, never()).apply(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void invalidSignature_is401_whenVerifierRejects() throws Exception {
        // callback-signatures-required=false en test : on force la vérification via le mock du
        // vérificateur en passant un en-tête Signature, ce que le contrôleur traite comme
        // « vérifier si présent OU si requis ».
        doThrow(new PawapaySignatureException("Signature invalide")).when(verifier)
                .verify(any(), any(), any(), any(), any());
        mockMvc.perform(post("/pawapay/callbacks/deposits").contentType(MediaType.APPLICATION_JSON)
                        .header("Signature", "sig-pp=:AAAA:").header("Signature-Input", "sig-pp=();alg=\"x\"")
                        .header("Content-Digest", "sha-512=:AAAA:")
                        .content("{\"depositId\":\"" + UUID.randomUUID() + "\",\"status\":\"COMPLETED\"}"))
                .andExpect(status().isUnauthorized());
        verify(operations, never()).apply(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
