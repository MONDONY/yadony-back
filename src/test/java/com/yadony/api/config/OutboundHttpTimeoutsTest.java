package com.yadony.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutboundHttpTimeouts — délais des clients HTTP sortants (SMS, email)")
class OutboundHttpTimeoutsTest {

    @Test
    @DisplayName("connexion 5 s, lecture 15 s")
    void requestFactoryCarriesBothTimeouts() {
        SimpleClientHttpRequestFactory factory = OutboundHttpTimeouts.requestFactory();

        assertThat(OutboundHttpTimeouts.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(OutboundHttpTimeouts.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(15));
        assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(5_000);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(15_000);
    }

    @Test
    @DisplayName("chaque appel obtient sa propre fabrique : un réglage local ne fuit pas ailleurs")
    void eachCallReturnsAFreshFactory() {
        assertThat(OutboundHttpTimeouts.requestFactory()).isNotSameAs(OutboundHttpTimeouts.requestFactory());
    }

    @Test
    @DisplayName("le RestTemplate des SMS utilise ces délais")
    void smsRestTemplateUsesTheTimeouts() {
        RestTemplate restTemplate = new StorageConfig().restTemplate();

        assertThat(restTemplate.getRequestFactory()).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(restTemplate.getRequestFactory(), "readTimeout")).isEqualTo(15_000);
    }

    @Test
    @DisplayName("un serveur muet coupe l'appel au délai de lecture au lieu de bloquer le thread")
    void silentServerTimesOut() throws Exception {
        SimpleClientHttpRequestFactory factory = OutboundHttpTimeouts.requestFactory();
        factory.setReadTimeout(Duration.ofMillis(300)); // même mécanisme, délai réduit pour le test
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket ignored = server.accept()) {
                    Thread.sleep(5_000);
                } catch (IOException | InterruptedException ignoredException) {
                    // fin du test
                }
            });
            ClientHttpRequest request = factory.createRequest(
                    URI.create("http://127.0.0.1:" + server.getLocalPort() + "/sms"), HttpMethod.GET);

            assertThatThrownBy(request::execute).isInstanceOf(SocketTimeoutException.class);
            acceptor.interrupt();
        }
    }
}
