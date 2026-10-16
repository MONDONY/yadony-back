package com.yadony.api.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Le SDK parle à un vrai HttpServer local : aucune requête ne part vers api.anthropic.com. */
class AnthropicMessagesClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "sk-ant-test-secret-key";

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<AnthropicMessagesClient> clients = new ArrayList<>();
    /** Corps JSON reçus par le serveur, dans l'ordre. */
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanup() {
        servers.forEach(s -> s.stop(0));
        clients.forEach(AnthropicMessagesClient::close);
    }

    private HttpServer server(HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-anthropic");
            t.setDaemon(true);
            return t;
        }));
        s.createContext("/", ex -> {
            received.add(MAPPER.readTree(ex.getRequestBody().readAllBytes()));
            handler.handle(ex);
        });
        s.start();
        servers.add(s);
        return s;
    }

    private AnthropicMessagesClient client(HttpServer s) {
        AnthropicMessagesClient c = new AnthropicMessagesClient(
                "http://127.0.0.1:" + s.getAddress().getPort(), Duration.ofSeconds(20));
        clients.add(c);
        return c;
    }

    private static LlmProviderConfig provider(String model) {
        return provider(model, KEY);
    }

    private static LlmProviderConfig provider(String model, String key) {
        return new LlmProviderConfig(UUID.fromString("00000000-0000-0000-0000-000000000001"), "claude",
                "https://api.anthropic.com", model, key, false, null);
    }

    private static String call(AnthropicMessagesClient c, LlmProviderConfig p) {
        return c.complete(p, "system prompt", "question", Duration.ofSeconds(10));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("request-id", "req_test");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        ex.close();
    }

    private static String message(String contentJson, String stopReason) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                + "\"content\":" + contentJson + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    private static String text(String value) throws IOException {
        return message("[{\"type\":\"text\",\"text\":" + MAPPER.writeValueAsString(value) + "}]", "end_turn");
    }

    private static String error(String type, String msg) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + msg + "\"},\"request_id\":\"req_test\"}";
    }

    private static void assertFails(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LlmCallException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.getCause()).isNull();
            assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("secret").doesNotContain("detail-du-fournisseur");
        });
    }

    // ---------- Requête et réponse ----------

    @ParameterizedTest
    @ValueSource(strings = {"claude-haiku-5-5", "claude-modele-futur-moins-cher-1"})
    void envoieLaConfigurationRapideAvecNImporteQuelModele(String model) throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer s = server(ex -> {
            path.set(ex.getRequestURI().getPath());
            apiKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            version.set(ex.getRequestHeaders().getFirst("anthropic-version"));
            authorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, text("{\"answer\":\"ok\"}"));
        });

        assertThat(call(client(s), provider(model))).isEqualTo("{\"answer\":\"ok\"}");

        assertThat(path.get()).isEqualTo("/v1/messages");
        assertThat(apiKey.get()).isEqualTo(KEY);
        assertThat(version.get()).isNotBlank();
        assertThat(authorization.get()).isNull();
        assertThat(received).hasSize(1);
        JsonNode body = received.get(0);
        assertThat(body.path("model").asText()).isEqualTo(model);
        assertThat(body.path("max_tokens").asLong()).isEqualTo(AnthropicMessagesClient.FAST_MAX_TOKENS);
        assertThat(body.path("system").asText()).isEqualTo("system prompt");
        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("question");
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("stream")).isFalse();
    }

    @Test
    void assembleLesBlocsTexteEtIgnoreLaReflexion() throws Exception {
        HttpServer s = server(ex -> respond(ex, 200, message(
                "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},"
                        + "{\"type\":\"text\",\"text\":\"{\\\"answer\\\":\"},{\"type\":\"text\",\"text\":\"\\\"ok\\\"}\"}]",
                "end_turn")));
        assertThat(call(client(s), provider("claude-haiku-5-5"))).isEqualTo("{\"answer\":\"ok\"}");
    }

    @Test
    void reutiliseLeClientDuFournisseurEtLeRemplaceQuandLaCleChange() throws Exception {
        HttpServer s = server(ex -> respond(ex, 200, text("ok")));
        AnthropicMessagesClient c = client(s);
        call(c, provider("claude-haiku-5-5"));
        call(c, provider("claude-haiku-5-5"));
        assertThat(c.openClients()).isEqualTo(1);
        AtomicReference<String> apiKey = new AtomicReference<>();
        servers.get(0).removeContext("/");
        servers.get(0).createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            apiKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            respond(ex, 200, text("ok"));
        });
        call(c, provider("claude-haiku-5-5", "sk-ant-nouvelle-cle"));
        assertThat(apiKey.get()).isEqualTo("sk-ant-nouvelle-cle");
        assertThat(c.openClients()).isEqualTo(1);
    }

    // ---------- Second essai sur paramètre non supporté ----------

    @Test
    void reessaieSansReflexionQuandLeModeleRefuseThinkingDisabled() throws Exception {
        HttpServer s = server(ex -> {
            if (received.size() == 1) {
                respond(ex, 400, error("invalid_request_error",
                        "thinking.type: disabled is not supported for this model. detail-du-fournisseur"));
            } else {
                respond(ex, 200, text("ok"));
            }
        });
        AnthropicMessagesClient c = client(s);

        assertThat(call(c, provider("claude-opus-5-5"))).isEqualTo("ok");

        assertThat(received).hasSize(2);
        JsonNode retry = received.get(1);
        assertThat(retry.has("thinking")).isFalse();
        assertThat(retry.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(retry.path("max_tokens").asLong()).isEqualTo(AnthropicMessagesClient.FALLBACK_MAX_TOKENS);
        assertThat(c.rememberedVariant("claude-opus-5-5")).contains(AnthropicMessagesClient.Variant.LOW_EFFORT);

        // La variante retenue sert directement à la question suivante : plus de 400 à payer.
        call(c, provider("claude-opus-5-5"));
        assertThat(received).hasSize(3);
        assertThat(received.get(2).has("thinking")).isFalse();
        // Un autre modèle repart de la configuration rapide.
        call(c, provider("claude-haiku-5-5"));
        assertThat(received.get(3).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void reessaieSansEffortNiReflexionQuandLeModeleNeConnaitPasEffort() throws Exception {
        HttpServer s = server(ex -> {
            if (received.size() == 1) {
                respond(ex, 400, error("invalid_request_error", "output_config.effort: Extra inputs are not permitted"));
            } else {
                respond(ex, 200, text("ok"));
            }
        });
        AnthropicMessagesClient c = client(s);
        assertThat(call(c, provider("claude-haiku-4-5"))).isEqualTo("ok");
        assertThat(received).hasSize(2);
        assertThat(received.get(1).has("thinking")).isFalse();
        assertThat(received.get(1).has("output_config")).isFalse();
        assertThat(c.rememberedVariant("claude-haiku-4-5")).contains(AnthropicMessagesClient.Variant.PLAIN);
    }

    @Test
    void unSeulSecondEssai() throws Exception {
        HttpServer s = server(ex -> respond(ex, 400, error("invalid_request_error", "thinking: not supported")));
        AnthropicMessagesClient c = client(s);
        assertFails(() -> call(c, provider("claude-x")), 400);
        assertThat(received).hasSize(2);
        assertThat(c.rememberedVariant("claude-x")).isEmpty();
    }

    @Test
    void unAutre400NeDeclenchePasDeSecondEssai() throws Exception {
        HttpServer s = server(ex -> respond(ex, 400, error("invalid_request_error",
                "messages: text content blocks must be non-empty. detail-du-fournisseur")));
        assertFails(() -> call(client(s), provider("claude-haiku-5-5")), 400);
        assertThat(received).hasSize(1);
    }

    @Test
    void choixDeLaVarianteSuivante() {
        assertThat(AnthropicMessagesClient.fallbackFor(AnthropicMessagesClient.Variant.FAST, "thinking.type disabled"))
                .contains(AnthropicMessagesClient.Variant.LOW_EFFORT);
        assertThat(AnthropicMessagesClient.fallbackFor(AnthropicMessagesClient.Variant.FAST, "effort not supported"))
                .contains(AnthropicMessagesClient.Variant.PLAIN);
        assertThat(AnthropicMessagesClient.fallbackFor(AnthropicMessagesClient.Variant.FAST, "TEMPERATURE"))
                .contains(AnthropicMessagesClient.Variant.PLAIN);
        assertThat(AnthropicMessagesClient.fallbackFor(AnthropicMessagesClient.Variant.LOW_EFFORT, "effort"))
                .contains(AnthropicMessagesClient.Variant.PLAIN);
        assertThat(AnthropicMessagesClient.fallbackFor(AnthropicMessagesClient.Variant.PLAIN, "thinking")).isEmpty();
        assertThat(AnthropicMessagesClient.fallbackFor(AnthropicMessagesClient.Variant.FAST, "model: invalid")).isEmpty();
        assertThat(AnthropicMessagesClient.fallbackFor(AnthropicMessagesClient.Variant.FAST, null)).isEmpty();
    }

    // ---------- Échecs : statut HTTP réel, jamais de relance du SDK ----------

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 529})
    void renvoieLeStatutHttpSansRelance(int status) throws Exception {
        HttpServer s = server(ex -> respond(ex, status, error("api_error", "detail-du-fournisseur " + KEY)));
        assertFails(() -> call(client(s), provider("claude-haiku-5-5")), status);
        assertThat(received).hasSize(1);
    }

    @Test
    void cleAbsenteRefuseeSansAppel() throws Exception {
        HttpServer s = server(ex -> respond(ex, 200, text("ok")));
        assertFails(() -> call(client(s), provider("claude-haiku-5-5", " ")), 401);
        assertThat(received).isEmpty();
    }

    @Test
    @Timeout(10)
    void delaiDepasse() throws Exception {
        HttpServer s = server(ex -> {
            try {
                Thread.sleep(4_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, text("trop tard"));
        });
        AnthropicMessagesClient c = client(s);
        long start = System.nanoTime();
        assertThatThrownBy(() -> c.complete(provider("claude-haiku-5-5"), "s", "u", Duration.ofMillis(600)))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.NETWORK));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3_000));
    }

    @Test
    void delaiPlafonneParLeMaximumDuClient() throws Exception {
        HttpServer s = server(ex -> {
            try {
                Thread.sleep(4_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(ex, 200, text("trop tard"));
        });
        AnthropicMessagesClient c = new AnthropicMessagesClient(
                "http://127.0.0.1:" + s.getAddress().getPort(), Duration.ofMillis(600));
        clients.add(c);
        long start = System.nanoTime();
        assertFails(() -> c.complete(provider("claude-haiku-5-5"), "s", "u", Duration.ofSeconds(60)), LlmCallException.NETWORK);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3_000));
    }

    @Test
    void reseauIndisponible() throws Exception {
        HttpServer s = server(ex -> respond(ex, 200, text("ok")));
        AnthropicMessagesClient c = client(s);
        s.stop(0);
        assertFails(() -> call(c, provider("claude-haiku-5-5")), LlmCallException.NETWORK);
    }

    // ---------- Réponses inexploitables ----------

    @Test
    void reponseVide() throws Exception {
        HttpServer s = server(ex -> respond(ex, 200, message("[]", "end_turn")));
        assertFails(() -> call(client(s), provider("claude-haiku-5-5")), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void maxTokensSansTexte() throws Exception {
        HttpServer s = server(ex -> respond(ex, 200,
                message("[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"}]", "max_tokens")));
        assertFails(() -> call(client(s), provider("claude-haiku-5-5")), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void refusDuModele() throws Exception {
        HttpServer s = server(ex -> respond(ex, 200, message(
                "[{\"type\":\"text\",\"text\":\"detail-du-fournisseur\"}]", "refusal")));
        assertFails(() -> call(client(s), provider("claude-haiku-5-5")), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void corpsIllisible() throws Exception {
        HttpServer s = server(ex -> respond(ex, 200, "pas du json detail-du-fournisseur"));
        assertThatThrownBy(() -> call(client(s), provider("claude-haiku-5-5")))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isIn(LlmCallException.INVALID_OUTPUT, LlmCallException.NETWORK);
                    assertThat(e.getMessage()).doesNotContain("detail-du-fournisseur");
                });
    }
}
