package com.yadony.api.emailotp;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient.RequestBodySpec;
import org.springframework.web.client.RestClient.RequestBodyUriSpec;
import org.springframework.web.client.RestClient.ResponseSpec;
import org.springframework.web.client.RestClient;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("ResendEmailService — tests unitaires")
class ResendEmailServiceTest {

    private RestClient mockRestClient;
    private RequestBodyUriSpec requestBodyUriSpec;
    private RequestBodySpec requestBodySpec;
    private ResponseSpec responseSpec;
    private ResendEmailService service;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        requestBodyUriSpec = mock(RequestBodyUriSpec.class);
        requestBodySpec = mock(RequestBodySpec.class);
        responseSpec = mock(ResponseSpec.class);

        when(mockRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/emails")).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);

        service = new ResendEmailService(
                "noreply@yadony.app",
                "Ton code : %s",
                mockRestClient,
                templateEngine());
    }

    @Test
    @DisplayName("sendOtp — exécute la chaîne RestClient vers /emails")
    void sendOtp_callsResendApi() {
        service.sendOtp("user@example.com", "123456");

        verify(mockRestClient).post();
    }

    @Test
    @DisplayName("sendOtp — sans clé Resend hors prod logge le code local sans appel externe")
    void sendOtp_withoutResendKeyOutsideProdSkipsExternalEmail() {
        service = new ResendEmailService(
                "noreply@yadony.app",
                "Ton code : %s",
                mockRestClient,
                templateEngine(),
                false,
                false,
                false);

        service.sendOtp("user@example.com", "123456");

        verify(mockRestClient, never()).post();
    }

    @Test
    @DisplayName("sendOtp — sans clé Resend en prod échoue avec une erreur de configuration")
    void sendOtp_withoutResendKeyInProdThrowsConfigurationError() {
        service = new ResendEmailService(
                "noreply@yadony.app",
                "Ton code : %s",
                mockRestClient,
                templateEngine(),
                false,
                false,
                true);

        assertThatThrownBy(() -> service.sendOtp("user@example.com", "123456"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getErrorCode()).isEqualTo("email-service-not-configured");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(mockRestClient, never()).post();
    }

    @Test
    @DisplayName("sendOtp — envoie le template HTML OTP avec logo public et texte fallback")
    void sendOtp_sendsHtmlTemplateAndTextFallback() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        service.sendOtp("user@example.com", "123456");

        verify(requestBodySpec).body(payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("from", "noreply@yadony.app")
                .containsEntry("subject", "Ton code Yadony")
                .containsEntry("text", "Ton code : 123456");
        assertThat(payload.get("to")).isEqualTo(List.of("user@example.com"));
        assertThat((String) payload.get("html"))
                .contains("https://yadony.com/logo.png")
                .contains("Vérification de compte")
                .contains("123456")
                .contains("Code valable 10 minutes")
                .contains("https://whatsapp.com/channel/0029VbCfmMzAYlUE5EddiT41")
                .contains("https://www.youtube.com/@yadony")
                .contains("https://www.tiktok.com/@yadony26")
                .contains("https://www.facebook.com/groups/1051558350756867")
                .contains("https://www.instagram.com/yadony2026/")
                .contains("© 2026 Yadony. Tous droits réservés.");
    }

    private static TemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");

        TemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
