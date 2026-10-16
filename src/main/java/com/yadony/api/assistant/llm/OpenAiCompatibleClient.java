package com.yadony.api.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client unique pour tous les fournisseurs compatibles OpenAI (Groq, Gemini, Mistral, OpenRouter…).
 *
 * <p>Les URL de base sont saisies par un administrateur : ce client est la frontière anti-SSRF.
 * Avant tout appel, {@link ProviderAddressGuard} refuse les hôtes non publics, et les redirections ne
 * sont jamais suivies (une redirection vers 169.254.169.254 contournerait la vérification).
 */
@Component
public class OpenAiCompatibleClient {

    private final RestClient restClient;
    private final ProviderAddressGuard guard;

    /** Seul constructeur annoté : Spring en voit un second (tests) et ne saurait pas lequel choisir. */
    @Autowired
    public OpenAiCompatibleClient(ProviderAddressGuard guard) {
        this(withTimeouts(), guard);
    }

    OpenAiCompatibleClient(RestClient restClient, ProviderAddressGuard guard) {
        this.restClient = restClient;
        this.guard = guard;
    }

    public String complete(LlmProviderConfig provider, String system, String user) {
        guard.check(provider.baseUrl());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        body.put("temperature", 0.2);
        body.put("max_tokens", 400);
        if (provider.supportsJsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(provider.baseUrl() + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection, (request, res) -> {
                        throw new LlmCallException(res.getStatusCode().value(), "Redirection refusée");
                    })
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new LlmCallException(e.getStatusCode().value(), shortMessage(e, provider.apiKey()));
        } catch (ResourceAccessException e) {
            throw new LlmCallException(LlmCallException.NETWORK, "Réseau indisponible ou délai dépassé");
        }
        JsonNode content = response == null ? null : response.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse sans contenu");
        }
        return content.asText();
    }

    private static String shortMessage(RestClientResponseException e, String apiKey) {
        String text = "HTTP " + e.getStatusCode().value() + " " + e.getResponseBodyAsString();
        if (apiKey != null && !apiKey.isEmpty()) text = text.replace(apiKey, "****");
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static RestClient withTimeouts() {
        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(
                        ClientHttpRequestFactoryBuilder.detect()
                                .build(ClientHttpRequestFactorySettings.defaults()
                                        .withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW)
                                        .withConnectTimeout(Duration.ofSeconds(5))
                                        .withReadTimeout(Duration.ofSeconds(15)))))
                .build();
    }
}
