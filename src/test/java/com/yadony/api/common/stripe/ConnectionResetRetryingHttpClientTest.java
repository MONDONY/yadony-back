package com.yadony.api.common.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comportement réel du SDK (aucun appel à Stripe : faux serveur local) face à la panne de
 * YADONY-BACK-STAGING-17. Le serveur coupe la connexion par un RST (« Connection reset by
 * peer ») sur les premières tentatives puis répond : la relance doit aboutir, et toutes les
 * tentatives d'un même POST doivent porter la même clé d'idempotence, celle de l'appelant
 * quand il en fournit une. Sans cela, une coupure APRÈS traitement chez Stripe doublerait
 * l'opération à la relance.
 */
class ConnectionResetRetryingHttpClientTest {

    private static final String PI_JSON =
            "{\"id\":\"pi_retry\",\"object\":\"payment_intent\",\"status\":\"requires_payment_method\"}";

    private ServerSocket server;
    private Thread acceptor;
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile int resetsBeforeSuccess;

    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serve, "fake-stripe");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        acceptor.join(2000);
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                int n = connections.incrementAndGet();
                String key = readIdempotencyKey(socket);
                idempotencyKeys.add(key);
                if (n <= resetsBeforeSuccess) {
                    socket.setSoLinger(true, 0); // close() émet un RST : « Connection reset »
                    continue;
                }
                byte[] body = PI_JSON.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nRequest-Id: req_test\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException e) {
                // serveur fermé en fin de test
            }
        }
    }

    private static String readIdempotencyKey(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String key = null;
        int contentLength = 0;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("idempotency-key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
            } else if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        for (int i = 0; i < contentLength; i++) {
            if (in.read() < 0) {
                break;
            }
        }
        return key;
    }

    private StripeClient client(int retries) {
        return StripeClient.builder()
                .setApiKey("sk_test_dummy")
                .setApiBase("http://127.0.0.1:" + server.getLocalPort())
                .setMaxNetworkRetries(retries)
                .setHttpClient(new ConnectionResetRetryingHttpClient(Duration.ofMillis(5)))
                .build();
    }

    /** Client du SDK nu, pour montrer la lacune que comble {@link ConnectionResetRetryingHttpClient}. */
    private StripeClient plainSdkClient(int retries) {
        return StripeClient.builder()
                .setApiKey("sk_test_dummy")
                .setApiBase("http://127.0.0.1:" + server.getLocalPort())
                .setMaxNetworkRetries(retries)
                .build();
    }

    private static PaymentIntentCreateParams params() {
        return PaymentIntentCreateParams.builder().setAmount(1000L).setCurrency("eur").build();
    }

    @Test
    void plainSdk_doesNotRetryAConnectionReset_evenWithMaxNetworkRetries() {
        // stripe-java ne relance que ConnectException et SocketTimeoutException : deux resets
        // d'affilée (la JVM n'en rattrape qu'un) remontent malgré maxNetworkRetries = 2.
        resetsBeforeSuccess = 2;

        assertThatThrownBy(() -> plainSdkClient(2).v1().paymentIntents().create(params()))
                .isInstanceOf(ApiConnectionException.class)
                .hasMessageContaining("Connection reset");
    }

    @Test
    void connectionReset_isRetried_withTheSameGeneratedIdempotencyKey() throws Exception {
        resetsBeforeSuccess = 2;

        PaymentIntent pi = client(2).v1().paymentIntents().create(params());

        assertThat(pi.getId()).isEqualTo("pi_retry");
        assertThat(connections.get()).isEqualTo(3);
        assertThat(idempotencyKeys).hasSize(3).doesNotContainNull();
        assertThat(idempotencyKeys).containsOnly(idempotencyKeys.get(0));
    }

    @Test
    void explicitIdempotencyKey_isKeptOnEveryRetry() throws Exception {
        resetsBeforeSuccess = 2;

        client(2).v1().paymentIntents().create(params(),
                RequestOptions.builder().setIdempotencyKey("capture-123").build());

        assertThat(idempotencyKeys).containsExactly("capture-123", "capture-123", "capture-123");
    }

    @Test
    void retriesExhausted_surfaceAsApiConnectionException() {
        resetsBeforeSuccess = 100;

        assertThatThrownBy(() -> client(1).v1().paymentIntents().create(params()))
                .isInstanceOf(ApiConnectionException.class);
        assertThat(idempotencyKeys).containsOnly(idempotencyKeys.get(0));
    }

    @Test
    void noRetryConfigured_failsOnTheFirstReset() {
        resetsBeforeSuccess = 100;

        assertThatThrownBy(() -> client(0).v1().paymentIntents().create(params()))
                .isInstanceOf(ApiConnectionException.class);
        // Seule la relance interne de la JVM (une) a pu s'ajouter, pas celle de ce client.
        assertThat(connections.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void isConnectionReset_excludesConnectRefusedAndOtherCauses() {
        assertThat(ConnectionResetRetryingHttpClient.isConnectionReset(
                new ApiConnectionException("x", new java.net.SocketException("Connection reset")))).isTrue();
        assertThat(ConnectionResetRetryingHttpClient.isConnectionReset(
                new ApiConnectionException("x", new java.net.ConnectException("refused")))).isFalse();
        assertThat(ConnectionResetRetryingHttpClient.isConnectionReset(
                new ApiConnectionException("x", new IOException("other")))).isFalse();
        assertThat(ConnectionResetRetryingHttpClient.isConnectionReset(
                new ApiConnectionException("x"))).isFalse();
    }

    @Test
    void interruptedWhileWaiting_stopsRetrying() {
        resetsBeforeSuccess = 100;
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client(2).v1().paymentIntents().create(params()))
                    .isInstanceOf(ApiConnectionException.class);
        } finally {
            Thread.interrupted();
        }
    }
}
