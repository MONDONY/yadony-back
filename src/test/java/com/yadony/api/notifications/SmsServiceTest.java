package com.yadony.api.notifications;

import com.yadony.api.config.PlatformSettingsTestFactory;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        smsService.send("+33612345678", "Votre code Yadony est : 123456");

        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
        verify(restTemplate, never()).postForEntity(contains("africastalking"), any(), eq(String.class));
    }

    @Test
    void send_corridorWithoutAfricasTalkingKey_goesDirectlyToTwilio() {
        // AT_API_KEY n'a jamais été posé : chaque SMS vers l'Afrique partait d'abord en 401
        // chez Africa's Talking, puis retombait sur Twilio. Sans clé, on va droit à Twilio.
        ReflectionTestUtils.setField(smsService, "atApiKey", "");
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        smsService.send("+221701234567", "Votre code Yadony est : 123456");

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
    // ── Numéro refusé par Twilio (Sentry YADONY-BACK-STAGING-2) ───────────────────
    // Twilio répond 400 avec un code d'erreur dans le corps. 21211 (« To » invalide) et
    // 21614 (pas un mobile) désignent le numéro saisi : c'est une erreur de l'utilisateur,
    // à lui remonter, pas un incident transporteur à logger en ERROR.

    private static HttpClientErrorException twilioBadRequest(int code, String message) {
        String body = "{\"code\":" + code + ",\"message\":\"" + message
                + "\",\"more_info\":\"https://www.twilio.com/docs/errors/" + code + "\",\"status\":400}";
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void sendViaTwilio_invalidToNumber_throwsInvalidRecipient_withoutLeakingThePhone() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioBadRequest(21211, "Invalid 'To' Phone Number: +22574884000"));

        assertThatThrownBy(() -> smsService.sendViaTwilio("+22574884000", "msg"))
                .isInstanceOf(InvalidSmsRecipientException.class)
                .hasMessageContaining("21211")
                .hasMessageNotContaining("74884");
    }

    @Test
    void sendViaTwilio_notAMobileNumber_throwsInvalidRecipient() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioBadRequest(21614, "'To' number is not a valid mobile number"));

        assertThatThrownBy(() -> smsService.sendViaTwilio("+33123456789", "msg"))
                .isInstanceOf(InvalidSmsRecipientException.class);
    }

    @Test
    void sendViaTwilio_otherClientError_isLoggedNotThrown() {
        // 21606 : le numéro « From » n'est pas valide — configuration côté plateforme,
        // rien à remonter à l'utilisateur.
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioBadRequest(21606, "The From phone number is not a valid, SMS-capable inbound phone number"));

        smsService.sendViaTwilio("+33612345678", "msg");
    }

    @Test
    void send_africasTalkingFails_thenTwilioRejectsRecipient_propagates() {
        when(restTemplate.postForEntity(contains("africastalking"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{}"));
        when(restTemplate.postForEntity(contains("twilio"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioBadRequest(21211, "Invalid 'To' Phone Number: +22574884000"));

        assertThatThrownBy(() -> smsService.send("+22574884000", "code"))
                .isInstanceOf(InvalidSmsRecipientException.class);
    }

    // ── Codes de connexion : l'échec du transporteur remonte (Sentry YADONY-BACK-PROD-G) ──
    // Le 08/11/2026, le compte Twilio suspendu répondait 401 et une route refusée 21612 :
    // send() les avalait, l'application affichait « code envoyé » pour des SMS jamais partis.

    private static HttpClientErrorException twilioUnauthorized() {
        return HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    void sendOrThrow_suspendedTwilioAccount401_throwsSmsDelivery_withoutLeakingThePhone() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioUnauthorized());

        assertThatThrownBy(() -> smsService.sendOrThrow("+33612345678", "code"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("612345678")
                .extracting(e -> ((SmsDeliveryException) e).getDeliveryFailure())
                .isEqualTo("HTTP 401");
    }

    @Test
    void sendOrThrow_routeRefused21612_throwsSmsDelivery_notInvalidRecipient() {
        // 21612 : couple expéditeur/destinataire refusé (numéro US vers le Sénégal). Le numéro
        // saisi est juste, lui demander de vérifier l'indicatif serait faux.
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioBadRequest(21612, "Message cannot be sent with the current combination of 'To' and/or 'From' parameters"));

        assertThatThrownBy(() -> smsService.sendOrThrow("+33612345678", "code"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("code 21612");
    }

    @Test
    void sendOrThrow_networkFailure_throwsSmsDelivery() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> smsService.sendOrThrow("+33612345678", "code"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("ResourceAccessException");
    }

    @Test
    void sendOrThrow_non2xxResponse_throwsSmsDelivery() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("{}"));

        assertThatThrownBy(() -> smsService.sendOrThrow("+33612345678", "code"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void sendOrThrow_success_doesNotThrow() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.CREATED).body("{}"));

        smsService.sendOrThrow("+33612345678", "code");

        verify(restTemplate).postForEntity(contains("twilio.com"), any(), eq(String.class));
    }

    @Test
    void sendOrThrow_invalidRecipient_stillThrowsInvalidRecipient() {
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioBadRequest(21211, "Invalid 'To' Phone Number"));

        assertThatThrownBy(() -> smsService.sendOrThrow("+22574884000", "code"))
                .isInstanceOf(InvalidSmsRecipientException.class);
    }

    @Test
    void sendOrThrow_smsDisabled_skipsWithoutThrowing() {
        // SMS coupés depuis le back-office : c'est SmsOtpService qui répond 503 en amont.
        disabledService().sendOrThrow("+33612345678", "code");

        verifyNoInteractions(restTemplate);
    }

    @Test
    void sendOrThrow_africasTalkingFails_thenTwilio401_throwsSmsDelivery() {
        when(restTemplate.postForEntity(contains("africastalking"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("error"));
        when(restTemplate.postForEntity(contains("twilio"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioUnauthorized());

        assertThatThrownBy(() -> smsService.sendOrThrow("+221701234567", "code"))
                .isInstanceOf(SmsDeliveryException.class);
    }

    @Test
    void send_suspendedTwilioAccount401_isLoggedNotThrown() {
        // Notifications : un échec du transporteur ne doit pas casser le traitement appelant.
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(twilioUnauthorized());

        smsService.send("+33612345678", "Votre colis est arrivé");

        verify(restTemplate).postForEntity(contains("twilio.com"), any(), eq(String.class));
    }

    // ── Pays bloqués depuis le back-office (sms_blocked_country_codes) ───────

    private SmsService serviceBlocking(String callingCode) {
        com.yadony.api.config.PlatformSettingsService settings = PlatformSettingsTestFactory.withSmsEnabled(true);
        when(settings.smsBlockedCallingCode(anyString())).thenAnswer(inv -> {
            String phone = inv.getArgument(0);
            return phone.startsWith("+" + callingCode)
                    ? java.util.Optional.of(callingCode) : java.util.Optional.empty();
        });
        SmsService service = new SmsService(restTemplate, settings);
        ReflectionTestUtils.setField(service, "atApiKey", "test-at-key");
        ReflectionTestUtils.setField(service, "atUsername", "sandbox");
        ReflectionTestUtils.setField(service, "atCorridorCallingCodes", java.util.List.of("221", "225", "223", "237"));
        ReflectionTestUtils.setField(service, "twilioAccountSid", "ACtest");
        ReflectionTestUtils.setField(service, "twilioAuthToken", "token");
        ReflectionTestUtils.setField(service, "twilioFrom", "+15005550006");
        return service;
    }

    @Test
    void send_blockedCallingCode_noHttpCallAndNoError() {
        // Repli SMS des notifications critiques : Twilio facturait des SMS « Delivered » vers
        // l'Afrique qui n'arrivaient jamais (08/11/2026). Rien ne part, rien n'est levé.
        SmsService service = serviceBlocking("225");

        service.send("+2250712345678", "Votre colis est livré");

        verifyNoInteractions(restTemplate);
    }

    @Test
    void sendOrThrow_blockedCallingCode_noHttpCallAndDeliveryException() {
        // Filet : SmsOtpService refuse avant, mais un code ne doit jamais être annoncé « envoyé ».
        SmsService service = serviceBlocking("225");

        assertThatThrownBy(() -> service.sendOrThrow("+2250712345678", "Votre code Yadony est : 123456"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("country-blocked +225");
        verifyNoInteractions(restTemplate);
    }

    @Test
    void send_otherCountryWhileAfricaBlocked_stillSent() {
        SmsService service = serviceBlocking("225");
        when(restTemplate.postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        service.send("+33612345678", "Votre colis est livré");

        verify(restTemplate).postForEntity(contains("twilio.com"), any(HttpEntity.class), eq(String.class));
    }
}
