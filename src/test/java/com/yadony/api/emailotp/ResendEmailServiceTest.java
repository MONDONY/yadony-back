package com.yadony.api.emailotp;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.gppro.events.GpProDecisionEvent;
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

    /** Logo servi par yadony-web (public/logos/). */
    private static final String LOGO_URL = "https://yadony.com/logos/logo-yadony-mail.png";
    /** Ancienne adresse du logo : 404 sur yadony.com, l'image arrivait cassée. */
    private static final String BROKEN_LOGO_URL = "https://yadony.com/logo.png";

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
                mockRestClient,
                templateEngine());
    }

    @Test
    @DisplayName("sendOtp — exécute la chaîne RestClient vers /emails")
    void sendOtp_callsResendApi() {
        service.sendOtp("user@example.com", "123456", TestMessages.fr());

        verify(mockRestClient).post();
    }

    @Test
    @DisplayName("sendOtp — sans clé Resend hors prod logge le code local sans appel externe")
    void sendOtp_withoutResendKeyOutsideProdSkipsExternalEmail() {
        service = new ResendEmailService(
                "noreply@yadony.app",
                mockRestClient,
                templateEngine(),
                false,
                false,
                false);

        service.sendOtp("user@example.com", "123456", TestMessages.fr());

        verify(mockRestClient, never()).post();
    }

    @Test
    @DisplayName("sendOtp — sans clé Resend en prod échoue avec une erreur de configuration")
    void sendOtp_withoutResendKeyInProdThrowsConfigurationError() {
        service = new ResendEmailService(
                "noreply@yadony.app",
                mockRestClient,
                templateEngine(),
                false,
                false,
                true);

        assertThatThrownBy(() -> service.sendOtp("user@example.com", "123456", TestMessages.fr()))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getErrorCode()).isEqualTo("email-service-not-configured");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(mockRestClient, never()).post();
    }

    @Test
    @DisplayName("sendOtp — français : sujet, texte et gabarit HTML dans la langue par défaut")
    void sendOtp_sendsHtmlTemplateAndTextFallback() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        service.sendOtp("user@example.com", "123456", TestMessages.fr());

        verify(requestBodySpec).body(payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("from", "noreply@yadony.app")
                .containsEntry("subject", "Votre code Yadony")
                .containsEntry("text", "Votre code Yadony est : 123456. Il expire dans 10 minutes. "
                        + "Si vous n'avez rien demandé, ignorez cet e-mail.");
        assertThat(payload.get("to")).isEqualTo(List.of("user@example.com"));
        assertThat((String) payload.get("html"))
                .contains(LOGO_URL)
                .doesNotContain(BROKEN_LOGO_URL)
                .contains("Voici votre code de vérification")
                // Le préheader porte le code : il se lit dans la notification.
                .contains("123456 est votre code Yadony")
                // Thymeleaf échappe l'apostrophe en HTML (th:text) : &#39;, pas '.
                .contains("Il expire dans 10 minutes et ne sert qu&#39;une fois.")
                .contains("Vous n&#39;avez rien demandé ?")
                .contains("https://whatsapp.com/channel/0029VbCfmMzAYlUE5EddiT41")
                .contains("https://www.youtube.com/@yadony")
                .contains("https://www.tiktok.com/@yadony_app")
                .contains("https://www.facebook.com/groups/1051558350756867")
                .contains("https://www.instagram.com/yadony2026/")
                .contains("© 2026 Yadony. Tous droits réservés.");
    }

    @Test
    @DisplayName("sendOtp — anglais : sujet, texte et gabarit HTML traduits")
    void sendOtp_english_translatesSubjectTextAndTemplate() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        service.sendOtp("user@example.com", "123456", TestMessages.en());

        verify(requestBodySpec).body(payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload)
                .containsEntry("subject", "Your Yadony code")
                .containsEntry("text", "Your Yadony code is: 123456. It expires in 10 minutes. "
                        + "If you didn't request it, ignore this email.");
        assertThat((String) payload.get("html"))
                .contains("Here is your verification code")
                .contains("123456 is your Yadony code")
                .contains("Didn&#39;t request this?")
                .doesNotContain("??");
    }

    @Test
    @DisplayName("sendGpProDecision — refus : le motif figure dans le texte et le HTML")
    void rejectedEmailContainsReason() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        service.sendGpProDecision("user@example.com", GpProDecisionEvent.Kind.REJECTED,
                "Pièce illisible.", TestMessages.fr());

        verify(requestBodySpec).body(payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload).containsEntry("subject", "Votre demande GP Pro a été refusée");
        assertThat((String) payload.get("text")).contains("Pièce illisible.");
        assertThat((String) payload.get("html")).contains("Pièce illisible.").contains(LOGO_URL).doesNotContain(BROKEN_LOGO_URL);
    }

    @Test
    @DisplayName("sendGpProDecision — la coche n'apparaît que pour une acceptation, le motif a sa propre ligne")
    void tickOnlyForApprovedAndReasonOnItsOwnLine() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        service.sendGpProDecision("user@example.com", GpProDecisionEvent.Kind.REJECTED,
                "Pièce illisible.", TestMessages.fr());
        service.sendGpProDecision("user@example.com", GpProDecisionEvent.Kind.APPROVED, null, TestMessages.fr());

        verify(requestBodySpec, times(2)).body(payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> rejected = (Map<String, Object>) payloadCaptor.getAllValues().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> approved = (Map<String, Object>) payloadCaptor.getAllValues().get(1);
        assertThat((String) rejected.get("html")).doesNotContain("&#10003;").doesNotContain("\u2713");
        assertThat((String) approved.get("html")).containsAnyOf("&#10003;", "\u2713");
        assertThat((String) rejected.get("text")).contains("\n\nMotif : Pièce illisible.\n\n");
    }

    @Test
    @DisplayName("sendGpProDecision — acceptation en anglais, sans motif")
    void approvedEmailEnglish() {
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        service.sendGpProDecision("user@example.com", GpProDecisionEvent.Kind.APPROVED, null, TestMessages.en());

        verify(requestBodySpec).body(payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload).containsEntry("subject", "You're a GP Pro on Yadony");
    }

    @Test
    @DisplayName("sendGpProDecision — un échec Resend est journalisé, jamais levé, même en prod")
    void gpProDecisionFailureDoesNotThrow() {
        when(requestBodySpec.retrieve()).thenThrow(new org.springframework.web.client.RestClientException("boom"));
        service = new ResendEmailService("noreply@yadony.app", mockRestClient, templateEngine(), true, false, true);

        org.assertj.core.api.Assertions.assertThatCode(() -> service.sendGpProDecision(
                "user@example.com", GpProDecisionEvent.Kind.REVOKED, "x", TestMessages.fr())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sendGpProDecision — sans clé Resend, rien n'est envoyé ni levé")
    void gpProDecisionNotConfigured() {
        service = new ResendEmailService("noreply@yadony.app", mockRestClient, templateEngine(), false, false, true);

        service.sendGpProDecision("user@example.com", GpProDecisionEvent.Kind.APPROVED, null, TestMessages.fr());

        verify(mockRestClient, never()).post();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastPayload() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec, atLeastOnce()).body(captor.capture());
        return (Map<String, Object>) captor.getValue();
    }

    @Test
    @DisplayName("sendNotification — titre et texte de la notification, ligne de la famille, bouton")
    void notificationEmail_recapInFrench() {
        service.sendNotification("user@example.com", com.yadony.api.notifications.NotificationEmailPolicy.Kind.RECAP,
                "DELIVERY_CONFIRMED", "Colis livré", "Fatou a reçu votre colis.", TestMessages.fr());

        Map<String, Object> payload = lastPayload();
        assertThat(payload).containsEntry("subject", "Colis livré").containsEntry("to", List.of("user@example.com"));
        assertThat((String) payload.get("html"))
                .contains("Colis livré").contains("Fatou a reçu votre colis.")
                .contains("Votre colis").contains("Ouvrir Yadony").contains("https://yadony.com")
                .contains("notez le voyageur")
                .contains("Vous pouvez couper ces e-mails")
                .contains(LOGO_URL).doesNotContain("??");
        assertThat((String) payload.get("text")).contains("Colis livré").contains("notez le voyageur");
    }

    @Test
    @DisplayName("sendNotification — le bouton mène à l'écran de la notification (lien universel), pas à l'accueil du site")
    void notificationEmail_buttonOpensTheScreen() {
        String url = "https://yadony.com/ouvrir?type=BID_ACCEPTED&bidId=0b9d1f3e-5a4c-4e8b-9a77-1c2d3e4f5a6b";
        service.sendNotification("user@example.com", com.yadony.api.notifications.NotificationEmailPolicy.Kind.RECAP,
                "BID_ACCEPTED", "Réservation acceptée", "b", url, TestMessages.fr());

        Map<String, Object> payload = lastPayload();
        assertThat((String) payload.get("html")).contains("Ouvrir Yadony").contains(url.replace("&", "&amp;"));
        assertThat((String) payload.get("text")).contains(url);
    }

    @Test
    @DisplayName("sendNotification — un reçu part toujours : pas de ligne pour couper, version anglaise")
    void notificationEmail_receiptInEnglish() {
        service.sendNotification("user@example.com", com.yadony.api.notifications.NotificationEmailPolicy.Kind.RECEIPT,
                "PAYMENT_RELEASED", "Payment sent", "Your earnings are on their way.", TestMessages.en());

        String html = (String) lastPayload().get("html");
        assertThat(html).contains("Receipt").contains("serves as your receipt").contains("Open Yadony")
                .doesNotContain("turn these emails off").doesNotContain("??");
    }

    @Test
    @DisplayName("sendNotification — bouton propre au type : son lien, son libellé, sa ligne à la place de la famille")
    void notificationEmail_withTypeCta() {
        String url = "https://api.yadony.com/api/v1/payments/onboarding/resume?t=abc.def";
        var cta = new com.yadony.api.notifications.NotificationEmailLink.Cta(url,
                "email.notif.cta.stripe-onboarding",
                "email.notif.type.STRIPE_ONBOARDING_INCOMPLETE.link-detail");

        service.sendNotification("user@example.com", com.yadony.api.notifications.NotificationEmailPolicy.Kind.ACCOUNT,
                "STRIPE_ONBOARDING_INCOMPLETE", "Finalisez vos paiements",
                "Il manque quelques informations pour être payé par carte.", cta, TestMessages.fr());

        Map<String, Object> payload = lastPayload();
        assertThat((String) payload.get("html"))
                .contains("Terminer mon inscription").contains("href=\"" + url + "\"")
                .contains("formulaire sécurisé de Stripe")
                .doesNotContain("Ouvrir Yadony").doesNotContain("se termine dans l")
                .doesNotContain("??");
        assertThat((String) payload.get("text"))
                .contains("Terminer mon inscription : " + url)
                .contains("valable 14 jours");

        service.sendNotification("user@example.com", com.yadony.api.notifications.NotificationEmailPolicy.Kind.ACCOUNT,
                "STRIPE_ONBOARDING_INCOMPLETE", "Finish your payment setup", "A few details are missing.",
                cta, TestMessages.en());
        assertThat((String) lastPayload().get("html")).contains("Finish my setup").contains("Stripe, our payment partner")
                .doesNotContain("??");
    }

    @Test
    @DisplayName("sendNotification — sans clé Resend rien ne part, un échec n'est jamais levé")
    void notificationEmail_notConfiguredOrFailing() {
        service = new ResendEmailService("noreply@yadony.app", mockRestClient, templateEngine(), false, false, true);
        service.sendNotification("user@example.com", com.yadony.api.notifications.NotificationEmailPolicy.Kind.DECISION,
                "DISPUTE_RESOLVED", "Litige résolu", "", TestMessages.fr());
        verify(mockRestClient, never()).post();

        when(requestBodySpec.retrieve()).thenThrow(new org.springframework.web.client.RestClientException("boom"));
        service = new ResendEmailService("noreply@yadony.app", mockRestClient, templateEngine(), true, false, true);
        org.assertj.core.api.Assertions.assertThatCode(() -> service.sendNotification("user@example.com",
                com.yadony.api.notifications.NotificationEmailPolicy.Kind.DECISION, "DISPUTE_RESOLVED",
                "Litige résolu", null, TestMessages.fr())).doesNotThrowAnyException();
    }

    private static TemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(TestMessages.source());
        return engine;
    }
}
