package com.yadony.api.calls;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class StreamWebhookVerifierTest {

    static final String SECRET = "s3cr3t-de-test-assez-long-pour-hs256-0123456789";
    final StreamWebhookVerifier verifier = new StreamWebhookVerifier(
            new StreamProperties(true, "u", "key", SECRET, "audio_call", 3));

    static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void signatureValide() throws Exception {
        byte[] body = "{\"type\":\"call.missed\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(body, sign(body), "key")).isTrue();
    }

    @Test
    void signatureEnMajusculesAcceptee() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(body, sign(body).toUpperCase(), "key")).isTrue();
    }

    @Test
    void signatureFausse() {
        assertThat(verifier.verify("{}".getBytes(), "00", "key")).isFalse();
    }

    @Test
    void signatureAbsente() {
        assertThat(verifier.verify("{}".getBytes(), null, "key")).isFalse();
    }

    @Test
    void mauvaiseCleApi() throws Exception {
        byte[] body = "{}".getBytes();
        assertThat(verifier.verify(body, sign(body), "autre")).isFalse();
    }

    @Test
    void secretVideFermeLaPorte() {
        var v = new StreamWebhookVerifier(new StreamProperties(true, "u", "key", "", "audio_call", 3));
        assertThat(v.verify("{}".getBytes(), "x", "key")).isFalse();
    }
}
