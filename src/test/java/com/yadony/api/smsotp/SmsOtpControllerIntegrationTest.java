package com.yadony.api.smsotp;

import com.yadony.api.common.YadonyBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.smsotp.dto.SmsOtpSendRequest;
import com.yadony.api.smsotp.dto.SmsOtpVerifyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("SmsOtpController — intégration MockMvc")
class SmsOtpControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private SmsOtpService smsOtpService;

    // ─── POST /auth/sms-otp/send ──────────────────────────────────────────────

    @Test
    @DisplayName("send 200 — retourne expiresAt")
    void send_success() throws Exception {
        Instant expiry = Instant.parse("2026-05-19T10:15:00Z");
        // Sans jeton : aucun compte connecté transmis (SmsOtpController#signedInUid).
        when(smsOtpService.sendOtp("+221701234567", null)).thenReturn(expiry);

        mockMvc.perform(post("/auth/sms-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SmsOtpSendRequest("+221701234567"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value("2026-05-19T10:15:00Z"));
    }

    @Test
    @DisplayName("send 422 — numéro invalide")
    void send_invalidPhone() throws Exception {
        mockMvc.perform(post("/auth/sms-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"not-a-phone\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("send 422 — numéro absent (body vide)")
    void send_missingPhone() throws Exception {
        mockMvc.perform(post("/auth/sms-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("send 429 — rate-limit atteint")
    void send_rateLimitExceeded() throws Exception {
        when(smsOtpService.sendOtp(anyString(), isNull()))
                .thenThrow(new YadonyBusinessException(
                        HttpStatus.TOO_MANY_REQUESTS, "phone-otp-rate-limit",
                        "Too Many Requests", "Trop de tentatives, réessaie dans 5 min"));

        mockMvc.perform(post("/auth/sms-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new SmsOtpSendRequest("+221701234567"))))
                .andExpect(status().isTooManyRequests());
    }

    // ─── POST /auth/sms-otp/verify ────────────────────────────────────────────

    @Test
    @DisplayName("verify 200 — retourne customToken")
    void verify_success() throws Exception {
        when(smsOtpService.verifyOtp("+221701234567", "123456"))
                .thenReturn("firebase-custom-token");

        mockMvc.perform(post("/auth/sms-otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new SmsOtpVerifyRequest("+221701234567", "123456"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customToken").value("firebase-custom-token"));
    }

    @Test
    @DisplayName("verify 400 — code invalide")
    void verify_invalidCode() throws Exception {
        when(smsOtpService.verifyOtp(anyString(), anyString()))
                .thenThrow(new YadonyBusinessException(
                        HttpStatus.BAD_REQUEST, "phone-otp-invalid",
                        "Invalid OTP", "Code invalide"));

        mockMvc.perform(post("/auth/sms-otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new SmsOtpVerifyRequest("+221701234567", "000000"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("verify 429 — trop de tentatives")
    void verify_tooManyAttempts() throws Exception {
        when(smsOtpService.verifyOtp(anyString(), anyString()))
                .thenThrow(new YadonyBusinessException(
                        HttpStatus.TOO_MANY_REQUESTS, "phone-otp-attempts-exceeded",
                        "Too Many Attempts", "Trop de tentatives"));

        mockMvc.perform(post("/auth/sms-otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new SmsOtpVerifyRequest("+221701234567", "000000"))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("verify 422 — code non numérique")
    void verify_nonNumericCode() throws Exception {
        mockMvc.perform(post("/auth/sms-otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+221701234567\",\"code\":\"abcdef\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ─── POST /auth/sms-otp/attach ────────────────────────────────────────────

    @Test
    @DisplayName("attach 401 — non authentifié (SecurityConfig exige .authenticated())")
    void attach_unauthenticated_401() throws Exception {
        mockMvc.perform(post("/auth/sms-otp/attach")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+221701234567\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }
}
