package com.yadony.api.assistant.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client unique pour tous les fournisseurs compatibles OpenAI (Groq, Gemini, Mistral, OpenRouter…).
 *
 * <p>Les URL de base sont saisies par un administrateur : ce client est la frontière anti-SSRF.
 * <ul>
 *   <li>{@link ProviderAddressGuard#check} refuse tôt les hôtes non publics (erreur claire).</li>
 *   <li>La connexion elle-même passe par le résolveur gardé : elle n'utilise que des adresses vérifiées,
 *       ce qui ferme le rebinding DNS (réponse publique pour la vérification, 127.0.0.1 pour la connexion).</li>
 *   <li>Les redirections ne sont jamais suivies.</li>
 *   <li>La réponse est lue au plus {@value #MAX_RESPONSE_BYTES} octets.</li>
 * </ul>
 * Aucun texte venant du fournisseur n'entre dans un message d'exception : il pourrait contenir un extrait
 * de la requête ou une clé partiellement masquée.
 */
@Component
public class OpenAiCompatibleClient {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final ProviderAddressGuard guard;

    /** Seul constructeur annoté : Spring en voit un second (tests) et ne saurait pas lequel choisir. */
    @Autowired
    public OpenAiCompatibleClient(ProviderAddressGuard guard) {
        this(buildRestClient(guard.asDnsResolver()), guard);
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
        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Requête illisible");
        }
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(provider.baseUrl() + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, res) -> readBounded(res.getStatusCode(), res.getBody()));
        } catch (ResourceAccessException e) {
            throw new LlmCallException(isBlocked(e) ? LlmCallException.BLOCKED_ADDRESS : LlmCallException.NETWORK,
                    isBlocked(e) ? "Adresse du fournisseur refusée" : "Réseau indisponible ou délai dépassé");
        } catch (RestClientException e) {
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse illisible");
        }
        JsonNode content = response == null ? null : response.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse sans contenu");
        }
        return content.asText();
    }

    /** Statut traité ici (jamais de texte du fournisseur), puis corps lu au plus MAX_RESPONSE_BYTES. */
    private static JsonNode readBounded(HttpStatusCode status, InputStream in) throws IOException {
        int code = status.value();
        if (status.is3xxRedirection()) {
            throw new LlmCallException(code, "Redirection refusée");
        }
        if (status.is4xxClientError() || status.is5xxServerError()) {
            throw new LlmCallException(code, "HTTP " + code);
        }
        byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse trop volumineuse");
        }
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "Réponse illisible");
        }
    }

    private static boolean isBlocked(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ProviderAddressGuard.BlockedAddressException) {
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    /**
     * Apache HttpClient 5 explicite : seul moyen de lui imposer le résolveur DNS gardé. Pas de redirections,
     * pas de cookies. Délais durs : connexion 5 s, lecture 15 s. Corps non mis en tampon, la lecture est bornée.
     */
    static RestClient buildRestClient(DnsResolver dnsResolver) {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(15))
                        .build())
                .build();
        CloseableHttpClient http = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableCookieManagement()
                .build();
        return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(http)).build();
    }
}
