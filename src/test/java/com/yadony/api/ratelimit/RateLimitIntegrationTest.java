package com.yadony.api.ratelimit;

import com.yadony.api.emailotp.EmailOtpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bout en bout dans Spring MVC : l'intercepteur est branché, le refus sort en RFC 7807
 * avec {@code Retry-After}, et les webhooks des prestataires y échappent.
 */
@SpringBootTest(properties = {
        "yadony.rate-limit.enabled=true",
        "yadony.rate-limit.rules.otp-send-ip.limit=2"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Rate limit — intégration MockMvc")
class RateLimitIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private EmailOtpService emailOtpService;

    private static MockHttpServletRequestBuilder sendOtp(String ip) {
        return post("/auth/email-otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; });
    }

    @Test
    @DisplayName("3e envoi OTP depuis la même IP → 429 too-many-requests, Retry-After et retryAfterSeconds")
    void otpSendLimitedPerIp() throws Exception {
        when(emailOtpService.sendOtp(anyString())).thenReturn(Instant.parse("2026-10-17T20:00:00Z"));

        mockMvc.perform(sendOtp("203.0.113.7")).andExpect(status().isOk());
        mockMvc.perform(sendOtp("203.0.113.7")).andExpect(status().isOk());

        mockMvc.perform(sendOtp("203.0.113.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("too-many-requests"))
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/too-many-requests"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(jsonPath("$.detail").isNotEmpty());

        // Une autre IP (autre abonné, autre opérateur) n'est pas touchée.
        mockMvc.perform(sendOtp("198.51.100.4")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("webhooks et callbacks des prestataires exclus de l'intercepteur")
    void webhooksExcluded() {
        // Un 429 y ferait perdre un événement (Didit ne rejoue pas) : l'exclusion doit
        // survivre à l'ajout d'une règle plus large que celles d'aujourd'hui.
        org.assertj.core.api.Assertions.assertThat(RateLimitConfig.EXCLUDED).contains(
                "/payments/webhook", "/kyc/webhook", "/kyc/webhook/**", "/billing/webhook",
                "/pawapay/callbacks/**", "/admin/sentry-webhook", "/calls/webhook", "/internal/**");
    }
}
