package com.yadony.api.assistant.llm;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleClientTest {

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final ProviderAddressGuard PUBLIC_GUARD = new ProviderAddressGuard(
            host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});

    /** Résolveur système sans garde : uniquement pour joindre un serveur local dans les tests de redirection. */
    private static final DnsResolver PERMISSIVE = new DnsResolver() {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return InetAddress.getAllByName(host);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    };

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

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LlmCallException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.getCause()).isNull();
        });
    }

    @Test
    void envoieLeModeleLesMessagesEtLeModeJson() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer gsk_secret_key"))
                .andExpect(header("Content-Type", "application/json"))
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
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(client.complete(provider(false), "s", "u")).isEqualTo("x");
    }

    @Test
    void erreurHttpNeLaisseNiLaCleNiLeCorpsDansLeMessage() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"invalid key gsk_secret_key, request was: question secrète\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.complete(provider(true), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.getMessage()).isEqualTo("HTTP 401");
                    assertThat(e.getMessage()).doesNotContain("gsk_secret_key").doesNotContain("secrète");
                });
    }

    @Test
    void erreurServeurDonneLeStatut() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertStatus(() -> client.complete(provider(true), "s", "u"), 429);
    }

    @Test
    void reponseSansContenuEstInvalide() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        assertStatus(() -> client.complete(provider(true), "s", "u"), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void contenuNulOuNonTexteEstInvalide() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":null}}]}", MediaType.APPLICATION_JSON));
        assertStatus(() -> client.complete(provider(true), "s", "u"), LlmCallException.INVALID_OUTPUT);
        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":42}}]}", MediaType.APPLICATION_JSON));
        assertStatus(() -> client.complete(provider(true), "s", "u"), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void jsonMalformeEstInvalideSansCause() {
        server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertStatus(() -> client.complete(provider(true), "s", "u"), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void corpsHtmlDUnProxyEstInvalideSansCause() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html><body>Bad gateway</body></html>", MediaType.TEXT_HTML));
        assertStatus(() -> client.complete(provider(true), "s", "u"), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void reponseDeLaTailleMaximaleEstAcceptee() {
        String prefix = "{\"choices\":[{\"message\":{\"content\":\"";
        String suffix = "\"}}]}";
        String content = "a".repeat(OpenAiCompatibleClient.MAX_RESPONSE_BYTES - prefix.length() - suffix.length());
        server.expect(requestTo(URL)).andRespond(withSuccess(prefix + content + suffix, MediaType.APPLICATION_JSON));
        assertThat(client.complete(provider(true), "s", "u")).isEqualTo(content);
    }

    @Test
    void reponseDUnOctetDeTropEstRefusee() {
        String prefix = "{\"choices\":[{\"message\":{\"content\":\"";
        String suffix = "\"}}]}";
        String content = "a".repeat(OpenAiCompatibleClient.MAX_RESPONSE_BYTES - prefix.length() - suffix.length() + 1);
        server.expect(requestTo(URL)).andRespond(withSuccess(prefix + content + suffix, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.complete(provider(true), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(LlmCallException.INVALID_OUTPUT);
                    assertThat(e.getMessage()).isEqualTo("Réponse trop volumineuse");
                });
    }

    @Test
    void panneReseauDonneNetwork() {
        server.expect(requestTo(URL))
                .andRespond(request -> { throw new SocketTimeoutException("Read timed out"); });
        assertStatus(() -> client.complete(provider(true), "s", "u"), LlmCallException.NETWORK);
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
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "http://169.254.169.254/latest/meta-data"));
        assertThatThrownBy(() -> client.complete(provider(true), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(302);
                    assertThat(e.getMessage()).isEqualTo("Redirection refusée");
                });
        server.verify();
    }

    @Test
    void leClientReelNeSuitPasLesRedirections() throws Exception {
        AtomicInteger cibleAtteinte = new AtomicInteger();
        HttpServer cible = localServer(ex -> {
            cibleAtteinte.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        String destination = "http://127.0.0.1:" + cible.getAddress().getPort() + "/metadata";
        HttpServer source = localServer(ex -> {
            ex.getResponseHeaders().add("Location", destination);
            ex.sendResponseHeaders(303, -1);
            ex.close();
        });
        try {
            // Résolveur permissif : le serveur de test est en boucle locale, la garde est simulée "publique".
            OpenAiCompatibleClient reel = new OpenAiCompatibleClient(
                    OpenAiCompatibleClient.buildRestClient(PERMISSIVE), PUBLIC_GUARD);
            assertThatThrownBy(() -> reel.complete(
                    provider(true, "http://127.0.0.1:" + source.getAddress().getPort() + "/v1"), "s", "u"))
                    .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.status()).isEqualTo(303));
            assertThat(cibleAtteinte).hasValue(0);
        } finally {
            source.stop(0);
            cible.stop(0);
        }
    }

    @Test
    void rebindingDnsNAtteintJamaisLeServeur() throws Exception {
        AtomicInteger requetes = new AtomicInteger();
        HttpServer local = localServer(ex -> {
            requetes.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        AtomicInteger resolutions = new AtomicInteger();
        // 1er appel (la garde) : adresse publique ; appels suivants (la connexion) : boucle locale.
        ProviderAddressGuard hostile = new ProviderAddressGuard(host ->
                resolutions.getAndIncrement() == 0
                        ? new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})}
                        : new InetAddress[]{InetAddress.getLoopbackAddress()});
        try {
            OpenAiCompatibleClient reel = new OpenAiCompatibleClient(hostile);
            assertThatThrownBy(() -> reel.complete(
                    provider(true, "http://rebind.example:" + local.getAddress().getPort() + "/v1"), "s", "u"))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            e -> assertThat(e.status()).isEqualTo(LlmCallException.BLOCKED_ADDRESS));
            assertThat(resolutions.get()).isGreaterThanOrEqualTo(2);
            assertThat(requetes).hasValue(0);
        } finally {
            local.stop(0);
        }
    }

    private static HttpServer localServer(HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", handler);
        s.start();
        return s;
    }
}
