package com.yadony.api.notifications;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TwilioVerifyService")
class TwilioVerifyServiceTest {

    private static final String US_PHONE = "+17135550123";
    private static final String START_URL = "https://verify.twilio.com/v2/Services/VAtest/Verifications";
    private static final String CHECK_URL = "https://verify.twilio.com/v2/Services/VAtest/VerificationCheck";

    @Mock RestTemplate restTemplate;

    TwilioVerifyService verify;

    @BeforeEach
    void setUp() {
        verify = configured("VAtest");
    }

    private TwilioVerifyService configured(String serviceSid) {
        TwilioVerifyService service = new TwilioVerifyService(restTemplate);
        ReflectionTestUtils.setField(service, "accountSid", "ACtest");
        ReflectionTestUtils.setField(service, "authToken", "token");
        ReflectionTestUtils.setField(service, "serviceSid", serviceSid);
        ReflectionTestUtils.setField(service, "callingCodes", List.of("1"));
        return service;
    }

    private static HttpClientErrorException twilioError(HttpStatus status, int code) {
        String body = "{\"code\":" + code + ",\"message\":\"x\",\"status\":" + status.value() + "}";
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("route — seuls les numéros +1 passent par Verify ; la France et l'Afrique non")
    void handles_onlyNorthAmerica() {
        assertThat(verify.handles(US_PHONE)).isTrue();            // États-Unis
        assertThat(verify.handles("+14165550123")).isTrue();      // Canada
        assertThat(verify.handles("+33612345678")).isFalse();     // France
        assertThat(verify.handles("+221701234567")).isFalse();    // Sénégal
        assertThat(verify.handles("17135550123")).isFalse();      // pas en E.164
        assertThat(verify.handles(null)).isFalse();
    }

    @Test
    @DisplayName("route — sans TWILIO_VERIFY_SERVICE_SID, rien ne passe par Verify")
    void handles_nothingWhenNotConfigured() {
        TwilioVerifyService notConfigured = configured("");

        assertThat(notConfigured.isConfigured()).isFalse();
        assertThat(notConfigured.handles(US_PHONE)).isFalse();
    }

    @Test
    @DisplayName("envoi — POST Verifications en SMS, avec la langue et l'authentification du compte")
    @SuppressWarnings("unchecked")
    void start_postsVerification() {
        when(restTemplate.postForEntity(eq(START_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(201).body("{\"status\":\"pending\"}"));

        verify.start(US_PHONE, "en", "QR5XSgGkFEN");

        ArgumentCaptor<HttpEntity<MultiValueMap<String, String>>> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq(START_URL), request.capture(), eq(String.class));
        MultiValueMap<String, String> body = request.getValue().getBody();
        assertThat(body.getFirst("To")).isEqualTo(US_PHONE);
        assertThat(body.getFirst("Channel")).isEqualTo("sms");
        assertThat(body.getFirst("Locale")).isEqualTo("en");
        assertThat(body.getFirst("AppHash")).isEqualTo("QR5XSgGkFEN");
        assertThat(request.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).startsWith("Basic ");
    }

    @Test
    @DisplayName("envoi — numéro refusé (60200) → InvalidSmsRecipientException")
    void start_invalidNumber() {
        when(restTemplate.postForEntity(eq(START_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioError(HttpStatus.BAD_REQUEST, 60200));

        assertThatThrownBy(() -> verify.start(US_PHONE, "fr", null))
                .isInstanceOf(InvalidSmsRecipientException.class);
    }

    @Test
    @DisplayName("envoi — service introuvable (20404) → VerifyUnavailableException")
    void start_serviceMissing() {
        when(restTemplate.postForEntity(eq(START_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioError(HttpStatus.NOT_FOUND, 20404));

        assertThatThrownBy(() -> verify.start(US_PHONE, "fr", null))
                .isInstanceOf(TwilioVerifyService.VerifyUnavailableException.class);
    }

    @Test
    @DisplayName("envoi — Twilio injoignable → VerifyUnavailableException")
    void start_unreachable() {
        when(restTemplate.postForEntity(eq(START_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> verify.start(US_PHONE, "fr", null))
                .isInstanceOf(TwilioVerifyService.VerifyUnavailableException.class);
    }

    @Test
    @DisplayName("contrôle — status approved → vrai")
    void check_approved() {
        when(restTemplate.postForEntity(eq(CHECK_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\": \"approved\",\"valid\":true}"));

        assertThat(verify.check(US_PHONE, "482913")).isTrue();
    }

    @Test
    @DisplayName("contrôle — status pending (mauvais code) → faux")
    void check_pending() {
        when(restTemplate.postForEntity(eq(CHECK_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"pending\",\"valid\":false}"));

        assertThat(verify.check(US_PHONE, "000000")).isFalse();
    }

    @Test
    @DisplayName("contrôle — vérification expirée (404) ou trop d'essais (429) → faux, pas une panne")
    void check_expiredOrExhausted() {
        when(restTemplate.postForEntity(eq(CHECK_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioError(HttpStatus.NOT_FOUND, 20404))
                .thenThrow(twilioError(HttpStatus.TOO_MANY_REQUESTS, 60202));

        assertThat(verify.check(US_PHONE, "482913")).isFalse();
        assertThat(verify.check(US_PHONE, "482913")).isFalse();
    }

    @Test
    @DisplayName("contrôle — identifiants refusés (401) → VerifyUnavailableException")
    void check_unauthorized() {
        when(restTemplate.postForEntity(eq(CHECK_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioError(HttpStatus.UNAUTHORIZED, 20003));

        assertThatThrownBy(() -> verify.check(US_PHONE, "482913"))
                .isInstanceOf(TwilioVerifyService.VerifyUnavailableException.class);
    }

    @Test
    @DisplayName("envoi — sans empreinte, aucun paramètre AppHash")
    @SuppressWarnings("unchecked")
    void start_withoutAppHash() {
        when(restTemplate.postForEntity(eq(START_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(201).body("{\"status\":\"pending\"}"));

        verify.start(US_PHONE, "fr", null);

        ArgumentCaptor<HttpEntity<MultiValueMap<String, String>>> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq(START_URL), request.capture(), eq(String.class));
        assertThat(request.getValue().getBody()).doesNotContainKey("AppHash");
    }
}
