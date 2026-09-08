package com.yadony.api.common.stripe;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.sentry.IScope;
import io.sentry.Sentry;
import io.sentry.ScopeCallback;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminAlertServiceTest {

    private final AdminAlertService service = new AdminAlertService();

    @Test
    void raise_doesNotThrow_withNonEmptyContext() {
        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            assertThatCode(() -> service.raise(
                    "STRIPE_DEAD_LETTER",
                    "Event evt_xxx could not be processed after 3 retries",
                    Map.of("eventId", "evt_xxx", "retryCount", 3)
            )).doesNotThrowAnyException();

            sentryMock.verify(() -> Sentry.withScope(any(ScopeCallback.class)), times(1));
        }
    }

    @Test
    void raise_doesNotThrow_withEmptyContext() {
        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            assertThatCode(() -> service.raise("TEST_CODE", "some detail", Map.of()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void raise_callsWithScope_toCaptureMessage() {
        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            // Allow withScope to actually execute the lambda so captureMessage is called
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(invocation -> {
                ScopeCallback callback = invocation.getArgument(0);
                IScope mockScope = mock(IScope.class);
                callback.run(mockScope);
                return null;
            });
            sentryMock.when(() -> Sentry.captureMessage(anyString())).thenReturn(null);

            service.raise("STRIPE_CHARGEBACK_OPENED", "Litige dp_001", Map.of("disputeId", "dp_001"));

            sentryMock.verify(() -> Sentry.captureMessage(contains("STRIPE_CHARGEBACK_OPENED")));
        }
    }

    @Test
    void raise_withoutTelegramConfig_doesNotCallRestClient() {
        RestClient restClient = mock(RestClient.class);
        AdminAlertService withoutConfig = new AdminAlertService(restClient, "", "", "test");

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            withoutConfig.raise("TEST_CODE", "some detail", Map.of());

            verifyNoInteractions(restClient);
        }
    }

    @Test
    void raise_withTelegramConfig_postsToTelegramApi() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), eq("bot-token-123"))).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        AdminAlertService withConfig = new AdminAlertService(restClient, "bot-token-123", "-100999", "staging");

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            withConfig.raise("KYC_IDENTITY_REJECTED", "Échec KYC user X", Map.of("userId", "u-1"));

            ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
            verify(bodySpec).body(bodyCaptor.capture());
            Map<String, String> body = bodyCaptor.getValue();
            assertThat(body.get("chat_id")).isEqualTo("-100999");
            String text = body.get("text");
            assertThat(text).contains("🪪")
                    .contains("Vérification KYC échouée")
                    .contains("KYC_IDENTITY_REJECTED")
                    .contains("Échec KYC user X")
                    .contains("userId : u-1")
                    .contains("Environnement : staging")
                    .contains("UTC");
        }
    }

    @Test
    void raise_withSentryIssueCode_usesBugEmojiAndDedicatedTitle() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), eq("bot-token-123"))).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        AdminAlertService withConfig = new AdminAlertService(restClient, "bot-token-123", "-100999", "staging");

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            withConfig.raise("SENTRY_ISSUE_CREATED", "NullPointerException in BidService", Map.of());

            ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
            verify(bodySpec).body(bodyCaptor.capture());
            String text = (String) bodyCaptor.getValue().get("text");
            assertThat(text).contains("🐛").contains("Nouvelle erreur Sentry");
        }
    }

    @Test
    void raise_whenTelegramCallFails_doesNotThrow() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), eq("bot-token-123"))).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenThrow(new RestClientException("network down"));

        AdminAlertService withConfig = new AdminAlertService(restClient, "bot-token-123", "-100999", "staging");

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            assertThatCode(() -> withConfig.raise("TEST_CODE", "detail", Map.of()))
                    .doesNotThrowAnyException();
        }
    }
    // --- Gravite par code : c'est elle qui decide du niveau de log, donc de la
    // metrique logback_events_total{level="error"} sur laquelle repose la regle
    // Grafana « Pic d'erreurs serveur ». ---

    private static ListAppender<ILoggingEvent> brancherAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AdminAlertService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void debrancherAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AdminAlertService.class);
        logger.detachAppender(appender);
    }

    private static AdminAlertService serviceAvecTelegram(RestClient.RequestBodySpec bodySpec) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), eq("bot-token-123"))).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
        return new AdminAlertService(restClient, "bot-token-123", "-100999", "prod");
    }

    @Test
    void graviteDe_classeChaqueFamilleDeCode() {
        assertThat(AdminAlertService.graviteDe("SUPPORT_TICKET_CREATED"))
                .isEqualTo(AdminAlertService.Gravite.INFO);
        assertThat(AdminAlertService.graviteDe("SENTRY_ISSUE_CREATED"))
                .isEqualTo(AdminAlertService.Gravite.AVERTISSEMENT);
        assertThat(AdminAlertService.graviteDe("STRIPE_PAYOUT_FAILED"))
                .isEqualTo(AdminAlertService.Gravite.INCIDENT);
        // Un code inconnu doit etre traite comme un incident, jamais minimise.
        assertThat(AdminAlertService.graviteDe("CODE_JAMAIS_VU"))
                .isEqualTo(AdminAlertService.Gravite.INCIDENT);
        assertThat(AdminAlertService.graviteDe(null))
                .isEqualTo(AdminAlertService.Gravite.INCIDENT);
    }

    @Test
    void raise_evenementMetier_neJournalisePasEnError() {
        ListAppender<ILoggingEvent> appender = brancherAppender();
        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            new AdminAlertService(mock(RestClient.class), "", "", "prod")
                    .raise("SUPPORT_TICKET_CREATED", "Nouveau ticket", Map.of("ticketId", "t-1"));

            List<ILoggingEvent> evenements = appender.list;
            assertThat(evenements).hasSize(1);
            assertThat(evenements.get(0).getLevel()).isEqualTo(Level.INFO);
        } finally {
            debrancherAppender(appender);
        }
    }

    @Test
    void raise_incident_journaliseBienEnError() {
        ListAppender<ILoggingEvent> appender = brancherAppender();
        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            new AdminAlertService(mock(RestClient.class), "", "", "prod")
                    .raise("STRIPE_PAYOUT_FAILED", "Virement refuse", Map.of());

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        } finally {
            debrancherAppender(appender);
        }
    }

    @Test
    void raise_avertissement_journaliseEnWarn() {
        ListAppender<ILoggingEvent> appender = brancherAppender();
        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            new AdminAlertService(mock(RestClient.class), "", "", "prod")
                    .raise("KYC_IDENTITY_REJECTED", "KYC refuse", Map.of());

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        } finally {
            debrancherAppender(appender);
        }
    }

    @Test
    void raise_alerteNeeDeSentry_neRepartPasVersSentry() {
        // Sinon : captureMessage cree une issue -> webhook Sentry -> raise() ->
        // captureMessage... Chaque tour porte un identifiant d'issue different,
        // donc rien ne dedoublonne la boucle.
        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            new AdminAlertService(mock(RestClient.class), "", "", "prod")
                    .raise("SENTRY_ISSUE_CREATED", "NPE dans BidService", Map.of("issueId", "YAD-42"));

            sentryMock.verify(() -> Sentry.withScope(any(ScopeCallback.class)), never());
        }
    }

    @Test
    void raise_evenementMetier_neRemontePasDansSentry() {
        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            new AdminAlertService(mock(RestClient.class), "", "", "prod")
                    .raise("SUPPORT_TICKET_CREATED", "Nouveau ticket", Map.of());

            sentryMock.verify(() -> Sentry.withScope(any(ScopeCallback.class)), never());
        }
    }

    @Test
    void raise_evenementMetier_envoieUneNotificationMuette() {
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        AdminAlertService service = serviceAvecTelegram(bodySpec);

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            service.raise("SUPPORT_TICKET_CREATED", "Nouveau ticket", Map.of("ticketId", "t-1"));

            ArgumentCaptor<Map> capteur = ArgumentCaptor.forClass(Map.class);
            verify(bodySpec).body(capteur.capture());
            Map<String, Object> corps = capteur.getValue();
            assertThat(corps.get("disable_notification")).isEqualTo(true);
            assertThat((String) corps.get("text"))
                    .contains("💬")
                    .contains("Nouveau ticket support");
        }
    }

    @Test
    void raise_incident_envoieUneNotificationSonore() {
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        AdminAlertService service = serviceAvecTelegram(bodySpec);

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            service.raise("STRIPE_EARLY_FRAUD_WARNING", "Fraude signalee", Map.of());

            ArgumentCaptor<Map> capteur = ArgumentCaptor.forClass(Map.class);
            verify(bodySpec).body(capteur.capture());
            assertThat(capteur.getValue().get("disable_notification")).isEqualTo(false);
        }
    }
    @Test
    void raise_choisitEmojiEtTitrePourChaqueFamilleDeCode() {
        Map<String, String> attendus = new java.util.LinkedHashMap<>();
        attendus.put("KYC_IDENTITY_CANCELED", "🪪");
        attendus.put("SENTRY_ISSUE_UNRESOLVED", "🐛");
        attendus.put("SUPPORT_TICKET_CREATED", "💬");
        attendus.put("STRIPE_ACCOUNT_DEAUTHORIZED", "🏦");
        attendus.put("STRIPE_CAPABILITY_LOST", "🏦");
        attendus.put("STRIPE_CHARGEBACK_OPENED", "⚠️");
        attendus.put("STRIPE_EARLY_FRAUD_WARNING", "⚠️");
        attendus.put("STRIPE_REFUND_FAILED", "💸");
        attendus.put("STRIPE_PAYOUT_FAILED", "💰");
        attendus.put("STRIPE_TRANSFER_REVERSED", "💰");
        attendus.put("STRIPE_DEAD_LETTER", "☠️");
        attendus.put("EXCHANGE_RATE_SYNC_FAILED", "💱");
        attendus.put("BILLING_CHECKOUT_UNRESOLVED_USER", "🚨");

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            attendus.forEach((code, emoji) -> {
                RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
                serviceAvecTelegram(bodySpec).raise(code, "detail", Map.of());

                ArgumentCaptor<Map> capteur = ArgumentCaptor.forClass(Map.class);
                verify(bodySpec).body(capteur.capture());
                String texte = (String) capteur.getValue().get("text");
                assertThat(texte).as("emoji du code %s", code).startsWith(emoji);
                // Aucun code connu ne doit retomber sur le libelle generique.
                assertThat(texte).as("titre du code %s", code)
                        .doesNotContain("Alerte système");
            });
        }
    }

    @Test
    void raise_codeInconnu_retombeSurLeLibelleGenerique() {
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        AdminAlertService service = serviceAvecTelegram(bodySpec);

        try (MockedStatic<Sentry> sentryMock = mockStatic(Sentry.class)) {
            sentryMock.when(() -> Sentry.withScope(any(ScopeCallback.class))).thenAnswer(inv -> null);

            service.raise("UN_CODE_QUI_NEXISTE_PAS", "detail", Map.of());

            ArgumentCaptor<Map> capteur = ArgumentCaptor.forClass(Map.class);
            verify(bodySpec).body(capteur.capture());
            assertThat((String) capteur.getValue().get("text"))
                    .startsWith("🚨")
                    .contains("Alerte système");
        }
    }
}
