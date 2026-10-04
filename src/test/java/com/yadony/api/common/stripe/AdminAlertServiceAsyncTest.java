package com.yadony.api.common.stripe;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L'appel Telegram ne doit pas tenir la requête qui lève l'alerte : le webhook Sentry a
 * mis jusqu'à 48 s à répondre sur staging en attendant api.telegram.org.
 */
class AdminAlertServiceAsyncTest {

    @Test
    void raise_rendLaMainAvantLEnvoiTelegram_quiPartSurLExecuteur() {
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

        List<Runnable> pending = new ArrayList<>();
        Executor deferred = pending::add;
        AdminAlertService service =
                new AdminAlertService(restClient, "bot-token-123", "-100999", "staging", deferred);

        service.raise("SENTRY_ISSUE_CREATED", "NullPointerException", Map.of("issueId", "FLUTTER-1"));

        verifyNoInteractions(restClient);
        assertThat(pending).hasSize(1);

        pending.get(0).run();

        verify(restClient).post();
        verify(bodySpec).body(any(Map.class));
    }
}
