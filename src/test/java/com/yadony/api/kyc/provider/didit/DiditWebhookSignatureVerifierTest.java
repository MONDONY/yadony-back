package com.yadony.api.kyc.provider.didit;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DiditWebhookSignatureVerifierTest {

    private static final String SECRET = "secret_partage";
    private static final long NOW = 1_760_000_000L;

    private final DiditWebhookSignatureVerifier verifier = new DiditWebhookSignatureVerifier(
            new DiditProperties("https://verification.didit.me", "cle", "wf_1", SECRET),
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    private static String hmac(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] raw(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void accepts_aWellSignedRecentPayload() throws Exception {
        String body = "{\"session_id\":\"sess_1\",\"status\":\"Approved\"}";

        assertThat(verifier.verify(raw(body), hmac(body), String.valueOf(NOW))).isTrue();
    }

    /** Les octets signés sont ceux transmis : un accent ne doit pas casser la vérification. */
    @Test
    void accepts_aBodyWithAccents() throws Exception {
        String body = "{\"reason\":\"Pièce illisible — réessayez\"}";

        assertThat(verifier.verify(raw(body), hmac(body), String.valueOf(NOW))).isTrue();
    }

    @Test
    void accepts_anUppercaseSignature() throws Exception {
        String body = "{\"status\":\"Approved\"}";

        assertThat(verifier.verify(raw(body), hmac(body).toUpperCase(Locale.ROOT), String.valueOf(NOW)))
                .isTrue();
    }

    @Test
    void rejects_aTamperedBody() throws Exception {
        String signed = "{\"status\":\"Declined\"}";
        String tampered = "{\"status\":\"Approved\"}";

        assertThat(verifier.verify(raw(tampered), hmac(signed), String.valueOf(NOW))).isFalse();
    }

    @Test
    void rejects_aSignatureFromAnotherSecret() {
        String body = "{\"status\":\"Approved\"}";

        assertThat(verifier.verify(raw(body),
                "0000000000000000000000000000000000000000000000000000000000000000",
                String.valueOf(NOW))).isFalse();
    }

    @Test
    void rejects_anOldTimestamp_replayProtection() throws Exception {
        String body = "{\"status\":\"Approved\"}";

        assertThat(verifier.verify(raw(body), hmac(body), String.valueOf(NOW - 301))).isFalse();
    }

    @Test
    void rejects_aTimestampTooFarInTheFuture() throws Exception {
        String body = "{\"status\":\"Approved\"}";

        assertThat(verifier.verify(raw(body), hmac(body), String.valueOf(NOW + 301))).isFalse();
    }

    @Test
    void accepts_atTheEdgeOfTheWindow() throws Exception {
        String body = "{\"status\":\"Approved\"}";

        assertThat(verifier.verify(raw(body), hmac(body), String.valueOf(NOW - 300))).isTrue();
    }

    @Test
    void rejects_missingOrUnreadableHeaders() throws Exception {
        String body = "{\"status\":\"Approved\"}";

        assertThat(verifier.verify(raw(body), null, String.valueOf(NOW))).isFalse();
        assertThat(verifier.verify(raw(body), "  ", String.valueOf(NOW))).isFalse();
        assertThat(verifier.verify(raw(body), hmac(body), null)).isFalse();
        assertThat(verifier.verify(raw(body), hmac(body), "pas-un-nombre")).isFalse();
        assertThat(verifier.verify(null, hmac(body), String.valueOf(NOW))).isFalse();
    }

    /** Sans secret configuré, rien n'est accepté : un environnement incomplet refuse tout. */
    @Test
    void rejects_everything_whenNoSecretIsConfigured() throws Exception {
        DiditWebhookSignatureVerifier unconfigured = new DiditWebhookSignatureVerifier(
                new DiditProperties("https://verification.didit.me", "cle", "wf_1", ""),
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
        String body = "{\"status\":\"Approved\"}";

        assertThat(unconfigured.verify(raw(body), hmac(body), String.valueOf(NOW))).isFalse();
    }
}
