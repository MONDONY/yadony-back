package com.yadony.api.notifications;

import com.yadony.api.config.PlatformSettingsTestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsServiceTest {

    @Mock RestTemplate restTemplate;

    SmsService smsService;

    @BeforeEach
    void setUp() {
        smsService = new SmsService(restTemplate, PlatformSettingsTestFactory.withSmsEnabled(true));
        // L etat des SMS vient desormais de PlatformSettingsService, pas d un champ @Value.
        ReflectionTestUtils.setField(smsService, "atApiKey", "test-at-key");
        ReflectionTestUtils.setField(smsService, "atUsername", "sandbox");
        ReflectionTestUtils.setField(smsService, "atCorridorCallingCodes",
                java.util.List.of("221", "225", "223", "237"));
        ReflectionTestUtils.setField(smsService, "twilioAccountSid", "ACtest");
        ReflectionTestUtils.setField(smsService, "twilioAuthToken", "token");
        ReflectionTestUtils.setField(smsService, "twilioFrom", "+15005550006");
    }

    @Test
    void isEnabled_followsThePlatformSetting() {
        // L'etat des SMS est un reglage plateforme editable, plus une property figee au
        // demarrage : le couper depuis le back-office doit couper le service lui-meme.
        assertThat(smsService.isEnabled()).isTrue();
        assertThat(disabledService().isEnabled()).isFalse();
    }

    private SmsService disabledService() {
        return new SmsService(restTemplate, PlatformSettingsTestFactory.withSmsEnabled(false));
    }

    @Test
    void isAfricasTalkingCorridor_matchesConfiguredCallingCodes() {
        assertThat(smsService.isAfricasTalkingCorridor("+221701234567")).isTrue(); // Sénégal
        assertThat(smsService.isAfricasTalkingCorridor("+2250712345678")).isTrue(); // Côte d'Ivoire
        assertThat(smsService.isAfricasTalkingCorridor("+22370123456")).isTrue(); // Mali
        assertThat(smsService.isAfricasTalkingCorridor("+237612345678")).isTrue(); // Cameroun
        assertThat(smsService.isAfricasTalkingCorridor("+33612345678")).isFalse(); // France
        assertThat(smsService.isAfricasTalkingCorridor(null)).isFalse();
        assertThat(smsService.isAfricasTalkingCorridor("0612345678")).isFalse(); // pas E.164
    }

    @Test
    void send_frenchNumber_goesDirectlyToTwilio_noAfricasTalkingCall() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        smsService.send("+33612345678", "Ton code Yadony est : 123456");

        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
        verify(restTemplate, never()).postForEntity(contains("africastalking"), any(), eq(String.class));
    }

    @Test
    void devMode_logsAndSkipsHttpCall() {
        // Une coupure decidee depuis le back-office doit arreter l'envoi REEL, pas seulement
        // masquer un bouton : aucun appel au transporteur ne doit partir.
        disabledService().send("+221701234567", "Test message");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void send_africasTalkingSuccess_noTwilioFallback() {
        when(restTemplate.postForEntity(contains("africastalking"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        smsService.send("+221701234567", "Livraison confirmée");

        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void send_africasTalkingFails_fallsBackToTwilio() {
        when(restTemplate.postForEntity(contains("africastalking"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error"));
        when(restTemplate.postForEntity(contains("twilio"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        smsService.send("+221701234567", "Paiement reçu");

        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void send_africasTalkingThrows_fallsBackToTwilio() {
        when(restTemplate.postForEntity(contains("africastalking"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));
        when(restTemplate.postForEntity(contains("twilio"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        smsService.send("+221701234567", "Litige ouvert");

        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void sendViaAfricasTalking_includesPhoneAndMessage() {
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        boolean result = smsService.sendViaAfricasTalking("+221701234567", "Message test");

        assertThat(result).isTrue();
        ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(String.class));
        assertThat(captor.getValue().getBody().toString()).contains("221701234567");
    }

    @Test
    void sendViaAfricasTalking_returnsFalseOnError() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("timeout"));

        boolean result = smsService.sendViaAfricasTalking("+221701234567", "msg");

        assertThat(result).isFalse();
    }

    @Test
    void sendViaTwilio_usesTwilioEndpoint() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body("{}"));

        smsService.sendViaTwilio("+221701234567", "Message Twilio");

        verify(restTemplate).postForEntity(contains("twilio.com"), any(), eq(String.class));
    }
}