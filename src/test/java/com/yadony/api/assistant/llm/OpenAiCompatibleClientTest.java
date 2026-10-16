package com.yadony.api.assistant.llm;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleClientTest {

    private static final ProviderAddressGuard PUBLIC_GUARD = new ProviderAddressGuard(
            host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient(builder.build(), PUBLIC_GUARD);

    private static LlmProviderConfig provider(boolean json) {
        return provider(json, "https://api.groq.com/openai/v1");
    }

    private static LlmProviderConfig provider(boolean json, String baseUrl) {
        return new LlmProviderConfig(UUID.randomUUID(), "groq", baseUrl,
                "llama-3.3-70b-versatile", "gsk_secret_key", json, null);
    }

    @Test
    void envoieLeModeleLesMessagesEtLeModeJson() {
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer gsk_secret_key"))
                .andExpect(jsonPath("$.model").value("llama-3.3-70b-versatile"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("question"))
                .andExpect(jsonPath("$.temperature").value(0.2))
                .andExpect(jsonPath("$.max_tokens").value(400))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"answer\\\":\\\"ok\\\"}\"}}]}",
                        MediaType.APPLICATION_JSON));
        assertThat(client.complete(provider(true), "system", "question")).isEqualTo("{\"answer\":\"ok\"}");
        server.verify();
    }

    @Test
    void sansModeJsonNEnvoiePasResponseFormat() {
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(client.complete(provider(false), "s", "u")).isEqualTo("x");
    }

    @Test
    void erreurHttpDonneLeStatutSansLaCle() {
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":\"quota for key gsk_secret_key exceeded\"}").contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.complete(provider(true), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.getMessage()).doesNotContain("gsk_secret_key");
                });
    }

    @Test
    void reponseSansContenuEstInvalide() {
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.complete(provider(true), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.status()).isEqualTo(LlmCallException.INVALID_OUTPUT));
    }

    @Test
    void panneReseauDonneNetwork() {
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("Read timed out"); });
        assertThatThrownBy(() -> client.complete(provider(true), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.status()).isEqualTo(LlmCallException.NETWORK));
    }

    @Test
    void hoteNonPublicNAtteintJamaisLeServeur() {
        ProviderAddressGuard loopback = new ProviderAddressGuard(
                host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
        OpenAiCompatibleClient blocked = new OpenAiCompatibleClient(builder.build(), loopback);
        // Aucune attente déclarée : toute requête émise ferait échouer le serveur factice.
        assertThatThrownBy(() -> blocked.complete(provider(true, "http://localhost:8080/v1"), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(LlmCallException.BLOCKED_ADDRESS);
                    assertThat(e.getMessage()).doesNotContain("gsk_secret_key");
                });
        server.verify();
    }

    @Test
    void uneRedirectionEstRefuseeSansEtreSuivie() {
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "http://169.254.169.254/latest/meta-data"));
        assertThatThrownBy(() -> client.complete(provider(true), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.status()).isEqualTo(302));
        server.verify();
    }

    @Test
    void leClientReelNeSuitPasLesRedirections() throws Exception {
        java.util.concurrent.atomic.AtomicInteger cibleAtteinte = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer cible = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        cible.createContext("/", ex -> {
            cibleAtteinte.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        cible.start();
        com.sun.net.httpserver.HttpServer source = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String destination = "http://127.0.0.1:" + cible.getAddress().getPort() + "/metadata";
        source.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", destination);
            ex.sendResponseHeaders(303, -1);
            ex.close();
        });
        source.start();
        try {
            OpenAiCompatibleClient reel = new OpenAiCompatibleClient(PUBLIC_GUARD);
            assertThatThrownBy(() -> reel.complete(
                    provider(true, "http://127.0.0.1:" + source.getAddress().getPort() + "/v1"), "s", "u"))
                    .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.status()).isEqualTo(303));
            assertThat(cibleAtteinte).hasValue(0);
        } finally {
            source.stop(0);
            cible.stop(0);
        }
    }
}
