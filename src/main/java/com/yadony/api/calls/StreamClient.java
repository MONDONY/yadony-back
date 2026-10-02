package com.yadony.api.calls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API serveur Stream Video. Seul point du back qui parle à Stream. */
@Component
public class StreamClient {

    public record StreamUser(UUID id, String name, String image) {}

    public static class StreamUnavailableException extends RuntimeException {
        public StreamUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    private final RestClient restClient;
    private final StreamProperties properties;
    private final StreamTokenService tokens;

    @Autowired
    public StreamClient(StreamProperties properties, StreamTokenService tokens) {
        this(withTimeouts(), properties, tokens);
    }

    StreamClient(RestClient restClient, StreamProperties properties, StreamTokenService tokens) {
        this.restClient = restClient;
        this.properties = properties;
        this.tokens = tokens;
    }

    public void upsertUsers(List<StreamUser> users) {
        Map<String, Object> byId = new LinkedHashMap<>();
        for (StreamUser u : users) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", u.id().toString());
            user.put("name", u.name());
            if (u.image() != null) user.put("image", u.image());
            byId.put(u.id().toString(), user);
        }
        post("/api/v2/users", Map.of("users", byId));
    }

    /** Crée l'appel et fait sonner les membres. La vidéo est coupée par le type d'appel (audio_call). */
    public void createRingingCall(String callId, UUID createdBy, List<UUID> memberIds) {
        List<Map<String, String>> members = memberIds.stream()
                .map(id -> Map.of("user_id", id.toString())).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created_by_id", createdBy.toString());
        data.put("members", members);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ring", true);
        body.put("video", false);
        body.put("data", data);
        post("/api/v2/video/call/" + properties.callType() + "/" + callId, body);
    }

    private void post(String path, Object body) {
        try {
            restClient.post()
                    .uri(properties.baseUrl() + path + "?api_key=" + properties.apiKey())
                    .header("Authorization", tokens.serverToken())
                    .header("stream-auth-type", "jwt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new StreamUnavailableException("Stream " + path + " a échoué", e);
        }
    }

    // Jamais SimpleClientHttpRequestFactory (redirection = corps perdu) ; Buffering évite l'envoi chunked.
    private static RestClient withTimeouts() {
        return RestClient.builder()
                .requestFactory(new BufferingClientHttpRequestFactory(
                        ClientHttpRequestFactoryBuilder.detect()
                                .build(ClientHttpRequestFactorySettings.defaults()
                                        .withConnectTimeout(Duration.ofSeconds(5))
                                        .withReadTimeout(Duration.ofSeconds(10)))))
                .build();
    }
}
