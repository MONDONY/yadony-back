package com.yadony.api.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tous les tests parlent à de vrais HttpServer en boucle locale (la garde est simulée « publique »). */
class OpenAiCompatibleClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ProviderAddressGuard PUBLIC_GUARD = new ProviderAddressGuard(
            host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});

    /** Résolveur système sans garde : uniquement pour joindre un serveur local dans les tests. */
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

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<OpenAiCompatibleClient> clients = new ArrayList<>();

    @AfterEach
    void cleanup() {
        servers.forEach(s -> s.stop(0));
        clients.forEach(OpenAiCompatibleClient::close);
    }

    private HttpServer server(HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-http");
            t.setDaemon(true);
            return t;
        }));
        s.createContext("/", handler);
        s.start();
        servers.add(s);
        return s;
    }

    private static String baseUrl(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + "/v1";
    }

    private OpenAiCompatibleClient client(Duration total) {
        OpenAiCompatibleClient c = new OpenAiCompatibleClient(PUBLIC_GUARD, PERMISSIVE, total);
        clients.add(c);
        return c;
    }

    private OpenAiCompatibleClient client() {
        return client(Duration.ofSeconds(20));
    }

    private static LlmProviderConfig provider(boolean json, String baseUrl) {
        return new LlmProviderConfig(UUID.randomUUID(), "groq", baseUrl,
                "llama-3.3-70b-versatile", "gsk_secret_key", json, null);
    }

    private static HttpHandler reply(int status, String contentType, String body) {
        return ex -> respond(ex, status, contentType, body);
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    private static String ok(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    private static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LlmCallException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.getCause()).isNull();
        });
    }

    // ---------- Contenu de la requête et de la réponse ----------

    @Test
    void envoieLeModeleLesMessagesEtLeModeJson() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> sent = new AtomicReference<>();
        HttpServer s = server(ex -> {
            method.set(ex.getRequestMethod());
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            sent.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "application/json", ok("{\\\"answer\\\":\\\"ok\\\"}"));
        });
        assertThat(client().complete(provider(true, baseUrl(s)), "system", "question")).isEqualTo("{\"answer\":\"ok\"}");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/v1/chat/completions");
        assertThat(auth.get()).isEqualTo("Bearer gsk_secret_key");
        assertThat(contentType.get()).startsWith("application/json");
        JsonNode body = MAPPER.readTree(sent.get());
        assertThat(body.path("model").asText()).isEqualTo("llama-3.3-70b-versatile");
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("question");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(400);
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");
    }

    @Test
    void sansModeJsonNEnvoiePasResponseFormat() throws Exception {
        AtomicReference<String> sent = new AtomicReference<>();
        HttpServer s = server(ex -> {
            sent.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "application/json", ok("x"));
        });
        assertThat(client().complete(provider(false, baseUrl(s)), "s", "u")).isEqualTo("x");
        assertThat(MAPPER.readTree(sent.get()).has("response_format")).isFalse();
    }

    @Test
    void erreurHttpNeLaisseNiLaCleNiLeCorpsDansLeMessage() throws Exception {
        HttpServer s = server(reply(401, "application/json",
                "{\"error\":\"invalid key gsk_secret_key, request was: question secrète\"}"));
        assertThatThrownBy(() -> client().complete(provider(true, baseUrl(s)), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.getMessage()).isEqualTo("HTTP 401");
                    assertThat(e.getMessage()).doesNotContain("gsk_secret_key").doesNotContain("secrète");
                });
    }

    @Test
    void erreurServeurDonneLeStatut() throws Exception {
        HttpServer s = server(reply(429, "application/json", ""));
        assertStatus(() -> client().complete(provider(true, baseUrl(s)), "s", "u"), 429);
    }

    @Test
    void reponseSansContenuEstInvalide() throws Exception {
        HttpServer s = server(reply(200, "application/json", "{\"choices\":[]}"));
        assertStatus(() -> client().complete(provider(true, baseUrl(s)), "s", "u"), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void contenuNulOuNonTexteEstInvalide() throws Exception {
        HttpServer nul = server(reply(200, "application/json", "{\"choices\":[{\"message\":{\"content\":null}}]}"));
        assertStatus(() -> client().complete(provider(true, baseUrl(nul)), "s", "u"), LlmCallException.INVALID_OUTPUT);
        HttpServer nombre = server(reply(200, "application/json", "{\"choices\":[{\"message\":{\"content\":42}}]}"));
        assertStatus(() -> client().complete(provider(true, baseUrl(nombre)), "s", "u"), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void jsonMalformeEstInvalideSansCause() throws Exception {
        HttpServer s = server(reply(200, "application/json", "not json"));
        assertStatus(() -> client().complete(provider(true, baseUrl(s)), "s", "u"), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void corpsHtmlDUnProxyEstInvalideSansCause() throws Exception {
        HttpServer s = server(reply(200, "text/html", "<html><body>Bad gateway</body></html>"));
        assertStatus(() -> client().complete(provider(true, baseUrl(s)), "s", "u"), LlmCallException.INVALID_OUTPUT);
    }

    @Test
    void reponseDeLaTailleMaximaleEstAcceptee() throws Exception {
        String prefix = "{\"choices\":[{\"message\":{\"content\":\"";
        String suffix = "\"}}]}";
        String content = "a".repeat(OpenAiCompatibleClient.MAX_RESPONSE_BYTES - prefix.length() - suffix.length());
        HttpServer s = server(reply(200, "application/json", prefix + content + suffix));
        assertThat(client().complete(provider(true, baseUrl(s)), "s", "u")).isEqualTo(content);
    }

    @Test
    void reponseDUnOctetDeTropEstRefusee() throws Exception {
        String prefix = "{\"choices\":[{\"message\":{\"content\":\"";
        String suffix = "\"}}]}";
        String content = "a".repeat(OpenAiCompatibleClient.MAX_RESPONSE_BYTES - prefix.length() - suffix.length() + 1);
        HttpServer s = server(reply(200, "application/json", prefix + content + suffix));
        assertThatThrownBy(() -> client().complete(provider(true, baseUrl(s)), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(LlmCallException.INVALID_OUTPUT);
                    assertThat(e.getMessage()).isEqualTo("Réponse trop volumineuse");
                });
    }

    @Test
    void panneReseauDonneNetwork() throws Exception {
        // Le serveur ferme la connexion sans rien répondre.
        HttpServer s = server(HttpExchange::close);
        assertStatus(() -> client().complete(provider(true, baseUrl(s)), "s", "u"), LlmCallException.NETWORK);
    }

    @Test
    void connexionRefuseeDonneNetwork() throws Exception {
        HttpServer s = server(reply(200, "application/json", ok("x")));
        String url = baseUrl(s);
        s.stop(0);
        assertStatus(() -> client().complete(provider(true, url), "s", "u"), LlmCallException.NETWORK);
    }

    // ---------- Anti-SSRF ----------

    @Test
    void hoteNonPublicNAtteintJamaisLeServeur() throws Exception {
        AtomicInteger requetes = new AtomicInteger();
        HttpServer s = server(ex -> {
            requetes.incrementAndGet();
            respond(ex, 200, "application/json", ok("x"));
        });
        ProviderAddressGuard loopback = new ProviderAddressGuard(
                host -> new InetAddress[]{InetAddress.getLoopbackAddress()});
        OpenAiCompatibleClient blocked = new OpenAiCompatibleClient(loopback, PERMISSIVE, Duration.ofSeconds(20));
        clients.add(blocked);
        assertThatThrownBy(() -> blocked.complete(provider(true, baseUrl(s)), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(LlmCallException.BLOCKED_ADDRESS);
                    assertThat(e.getMessage()).doesNotContain("gsk_secret_key");
                });
        assertThat(requetes).hasValue(0);
    }

    @Test
    void uneRedirectionEstRefuseeSansEtreSuivie() throws Exception {
        AtomicInteger cibleAtteinte = new AtomicInteger();
        HttpServer cible = server(ex -> {
            cibleAtteinte.incrementAndGet();
            respond(ex, 200, "application/json", ok("x"));
        });
        String destination = "http://127.0.0.1:" + cible.getAddress().getPort() + "/metadata";
        for (int code : new int[]{302, 303, 307}) {
            HttpServer source = server(ex -> {
                ex.getResponseHeaders().add("Location", destination);
                ex.sendResponseHeaders(code, -1);
                ex.close();
            });
            assertThatThrownBy(() -> client().complete(provider(true, baseUrl(source)), "s", "u"))
                    .isInstanceOfSatisfying(LlmCallException.class, e -> {
                        assertThat(e.status()).isEqualTo(code);
                        assertThat(e.getMessage()).isEqualTo("Redirection refusée");
                    });
        }
        assertThat(cibleAtteinte).hasValue(0);
    }

    @Test
    void rebindingDnsNAtteintJamaisLeServeur() throws Exception {
        AtomicInteger requetes = new AtomicInteger();
        HttpServer local = server(ex -> {
            requetes.incrementAndGet();
            respond(ex, 200, "application/json", ok("x"));
        });
        AtomicInteger resolutions = new AtomicInteger();
        // 1er appel (la garde) : adresse publique ; appels suivants (la connexion) : boucle locale.
        ProviderAddressGuard hostile = new ProviderAddressGuard(host ->
                resolutions.getAndIncrement() == 0
                        ? new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})}
                        : new InetAddress[]{InetAddress.getLoopbackAddress()});
        OpenAiCompatibleClient reel = new OpenAiCompatibleClient(hostile);
        clients.add(reel);
        assertThatThrownBy(() -> reel.complete(
                provider(true, "http://rebind.example:" + local.getAddress().getPort() + "/v1"), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.BLOCKED_ADDRESS));
        assertThat(resolutions.get()).isGreaterThanOrEqualTo(2);
        assertThat(requetes).hasValue(0);
    }

    // ---------- Délai total et abandon de la connexion (R18) ----------

    /** Corps chunked sans fin : {@code pauseMs} entre deux écritures, jusqu'à la déconnexion du client (borne 30 s). */
    private static HttpHandler endlessBody(int status, int chunkBytes, long pauseMs, AtomicInteger writes) {
        return ex -> {
            ex.sendResponseHeaders(status, 0);
            byte[] chunk = new byte[chunkBytes];
            Arrays.fill(chunk, (byte) 'a');
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            try (OutputStream out = ex.getResponseBody()) {
                while (System.nanoTime() < deadline) {
                    out.write(chunk);
                    out.flush();
                    writes.incrementAndGet();
                    if (pauseMs > 0) Thread.sleep(pauseMs);
                }
            } catch (IOException | InterruptedException ignored) {
                // le client a abandonné la connexion
            }
        };
    }

    private record Outcome(LlmCallException error, long millis) {}

    private static Outcome timed(Runnable call) {
        long start = System.nanoTime();
        LlmCallException error = null;
        try {
            call.run();
        } catch (LlmCallException e) {
            error = e;
        }
        return new Outcome(error, (System.nanoTime() - start) / 1_000_000);
    }

    @Test
    @Timeout(15)
    void corpsAuCompteGouttesSansFinEstCoupeParLeDelaiTotal() throws Exception {
        HttpServer s = server(endlessBody(200, 1, 200, new AtomicInteger()));
        OpenAiCompatibleClient c = client(Duration.ofSeconds(1));
        Outcome o = timed(() -> c.complete(provider(true, baseUrl(s)), "s", "u"));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(LlmCallException.NETWORK);
        assertThat(o.error().getMessage()).isEqualTo("Délai dépassé");
        assertThat(o.error().getCause()).isNull();
        assertThat(o.millis()).isLessThan(3000);
    }

    @Test
    @Timeout(15)
    void surchargeAvecDelaiExplicitEstCoupeeAvantLeDelaiParDefaut() throws Exception {
        HttpServer s = server(endlessBody(200, 1, 200, new AtomicInteger()));
        // Délai par défaut long : seul le délai passé à la surcharge peut expliquer l'arrêt rapide.
        OpenAiCompatibleClient c = client(Duration.ofSeconds(20));
        Outcome o = timed(() -> c.complete(provider(true, baseUrl(s)), "s", "u", Duration.ofSeconds(1)));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(LlmCallException.NETWORK);
        assertThat(o.error().getMessage()).isEqualTo("Délai dépassé");
        assertThat(o.error().getCause()).isNull();
        assertThat(o.millis()).isLessThan(3000);
    }

    @Test
    void delaiDejaEcouleApresLaGardeNeLanceAucunAppel() {
        ProviderAddressGuard lent = new ProviderAddressGuard(host -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new InetAddress[] {InetAddress.getByAddress(new byte[] {8, 8, 8, 8})};
        });
        OpenAiCompatibleClient c = new OpenAiCompatibleClient(lent, PERMISSIVE, Duration.ofSeconds(20));
        clients.add(c);
        Outcome o = timed(() -> c.complete(provider(true, "http://127.0.0.1:9/v1"), "s", "u", Duration.ofMillis(100)));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(LlmCallException.NETWORK);
        assertThat(o.error().getMessage()).isEqualTo("Délai dépassé");
        assertThat(o.error().getCause()).isNull();
    }

    @Test
    @Timeout(15)
    void serveurQuiNeRepondJamaisEstCoupeParLeDelaiTotal() throws Exception {
        HttpServer s = server(ex -> {
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException ignored) {
                // arrêt du test
            }
        });
        OpenAiCompatibleClient c = client(Duration.ofSeconds(1));
        Outcome o = timed(() -> c.complete(provider(true, baseUrl(s)), "s", "u"));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(LlmCallException.NETWORK);
        assertThat(o.millis()).isLessThan(3000);
    }

    @Test
    @Timeout(15)
    void corpsSansFinPlusGrosQueLeMaximumEstAbandonneSansLireLeReste() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        HttpServer s = server(endlessBody(200, 8192, 0, writes));
        // Délai total long : seul l'abandon de la connexion peut expliquer une réponse rapide.
        OpenAiCompatibleClient c = client(Duration.ofSeconds(10));
        Outcome o = timed(() -> c.complete(provider(true, baseUrl(s)), "s", "u"));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(LlmCallException.INVALID_OUTPUT);
        assertThat(o.error().getMessage()).isEqualTo("Réponse trop volumineuse");
        assertThat(o.millis()).isLessThan(3000);
    }

    @Test
    @Timeout(15)
    void erreur500AvecCorpsSansFinNeBloquePas() throws Exception {
        HttpServer s = server(endlessBody(500, 8192, 0, new AtomicInteger()));
        OpenAiCompatibleClient c = client(Duration.ofSeconds(10));
        Outcome o = timed(() -> c.complete(provider(true, baseUrl(s)), "s", "u"));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(500);
        assertThat(o.error().getMessage()).isEqualTo("HTTP 500");
        assertThat(o.millis()).isLessThan(3000);
    }

    @Test
    @Timeout(15)
    void redirectionAvecCorpsSansFinNeBloquePas() throws Exception {
        HttpServer s = server(ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:9/x");
            endlessBody(302, 8192, 0, new AtomicInteger()).handle(ex);
        });
        OpenAiCompatibleClient c = client(Duration.ofSeconds(10));
        Outcome o = timed(() -> c.complete(provider(true, baseUrl(s)), "s", "u"));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(302);
        assertThat(o.millis()).isLessThan(3000);
    }

    @Test
    @Timeout(15)
    void statutHorsPlageNEstJamaisLuCommeUnSucces() throws Exception {
        HttpServer s = server(reply(600, "application/json", ok("x")));
        assertStatus(() -> client().complete(provider(true, baseUrl(s)), "s", "u"), 600);
    }

    @Test
    @Timeout(15)
    void erreur503AvecCorpsSansFinNEstPasRelanceeNiDrainee() throws Exception {
        AtomicInteger requetes = new AtomicInteger();
        HttpServer s = server(ex -> {
            requetes.incrementAndGet();
            endlessBody(503, 8192, 0, new AtomicInteger()).handle(ex);
        });
        OpenAiCompatibleClient c = client(Duration.ofSeconds(10));
        Outcome o = timed(() -> c.complete(provider(true, baseUrl(s)), "s", "u"));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(503);
        assertThat(o.millis()).isLessThan(3000);
        assertThat(requetes).hasValue(1);
    }

    @Test
    @Timeout(15)
    void erreur429AvecRetryAfterNEstPasRelancee() throws Exception {
        AtomicInteger requetes = new AtomicInteger();
        HttpServer s = server(ex -> {
            requetes.incrementAndGet();
            ex.getResponseHeaders().add("Retry-After", "30");
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        OpenAiCompatibleClient c = client(Duration.ofSeconds(10));
        Outcome o = timed(() -> c.complete(provider(true, baseUrl(s)), "s", "u"));
        assertThat(o.error()).isNotNull();
        assertThat(o.error().status()).isEqualTo(429);
        assertThat(o.millis()).isLessThan(3000);
        assertThat(requetes).hasValue(1);
    }

    @Test
    void laRequeteNeDemandePasDeCompression() throws Exception {
        AtomicReference<String> acceptEncoding = new AtomicReference<>("absent");
        HttpServer s = server(ex -> {
            acceptEncoding.set(String.valueOf(ex.getRequestHeaders().getFirst("Accept-Encoding")));
            respond(ex, 200, "application/json", ok("x"));
        });
        assertThat(client().complete(provider(true, baseUrl(s)), "s", "u")).isEqualTo("x");
        assertThat(acceptEncoding.get()).isEqualTo("null");
    }

    @Test
    void clientFermeDonneUneLlmCallExceptionEtPasUneExceptionBrute() throws Exception {
        HttpServer s = server(reply(200, "application/json", ok("x")));
        OpenAiCompatibleClient c = client();
        c.close();
        assertThatThrownBy(() -> c.complete(provider(true, baseUrl(s)), "s", "u"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(LlmCallException.NETWORK);
                    assertThat(e.getMessage()).isEqualTo("Appel impossible");
                    assertThat(e.getCause()).isNull();
                });
    }
}
