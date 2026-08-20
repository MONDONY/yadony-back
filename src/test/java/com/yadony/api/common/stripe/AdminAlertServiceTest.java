package com.yadony.api.common.stripe;

import io.sentry.IScope;
import io.sentry.Sentry;
import io.sentry.ScopeCallback;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
}
