package com.yadony.api.kyc.provider.didit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que le client de production met reellement sur le fil, contre un vrai serveur HTTP.
 *
 * <p>Du 28/09 au 01/10 en staging, une partie des creations de session repondait
 * {@code 400 {"workflow_id":["This field is required."]}} alors que le workflow etait bien
 * configure : le meme utilisateur reessayait quelques secondes plus tard et la session
 * s'ouvrait. {@code httpclient5} (apporte par firebase-admin) est au classpath, donc
 * {@code ClientHttpRequestFactoryBuilder.detect()} choisit Apache HttpClient, qui envoyait le
 * JSON en flux : {@code Transfer-Encoding: chunked}, sans {@code Content-Length}. Didit
 * (Django) lisait alors par moments un corps vide. Un double de test (MockRestServiceServer)
 * ne voit pas l'encodage de transfert : seul un vrai serveur le revele.
 */
class DiditClientWireTest {

    private record Received(String method, String path, String contentLength,
                            String transferEncoding, String body) {}

    private HttpServer server;
    private final CopyOnWriteArrayList<Received> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        received.add(new Received(
                exchange.getRequestMethod(),
                path,
                exchange.getRequestHeaders().getFirst("Content-Length"),
                exchange.getRequestHeaders().getFirst("Transfer-Encoding"),
                body));

        if (path.equals("/redirige/v3/session/")) {
            exchange.getResponseHeaders().add("Location", "/v3/session/");
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
            return;
        }
        byte[] response = "{\"session_id\":\"s-1\",\"url\":\"https://verify.didit.me/s-1\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private DiditClient clientOn(String basePath) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + basePath;
        return new DiditClient(new DiditProperties(baseUrl, "cle", "wf-123", "secret", "sandbox"));
    }

    @Test
    void leCorpsPartAvecUnContentLengthEtSansEncodageChunked() {
        clientOn("").createSession(UUID.randomUUID(), "https://yadony.com/kyc/complete", "fr");

        assertThat(received).hasSize(1);
        Received request = received.get(0);
        assertThat(request.transferEncoding()).as("corps envoye en flux chunked").isNull();
        assertThat(request.contentLength()).isNotNull();
        assertThat(Integer.parseInt(request.contentLength()))
                .isEqualTo(request.body().getBytes(StandardCharsets.UTF_8).length);
        assertThat(request.body()).contains("\"workflow_id\":\"wf-123\"");
    }

    @Test
    void uneRedirectionConserveLeCorps() {
        clientOn("/redirige").createSession(UUID.randomUUID(), "https://yadony.com/kyc/complete", "fr");

        assertThat(received).hasSize(2);
        Received redirected = received.get(1);
        assertThat(redirected.method()).isEqualTo("POST");
        assertThat(redirected.path()).isEqualTo("/v3/session/");
        assertThat(redirected.body()).contains("\"workflow_id\":\"wf-123\"");
    }
}
