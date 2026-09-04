package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PawapaySignatureVerifierTest {

    private static KeyPair keys;
    private static final String KEY_ID = "HTTP_EC_P256_KEY:1";
    private static final String METHOD = "POST";
    private static final String AUTHORITY = "api.yadony.com";
    private static final String PATH = "/api/v1/pawapay/callbacks/deposits";
    private static final byte[] BODY = "{\"depositId\":\"abc\",\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        keys = gen.generateKeyPair();
    }

    private static Map<String, String> signedHeaders(byte[] body, long created, long expires, String keyId) throws Exception {
        String digest = "sha-512=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(body)) + ":";
        String date = Instant.ofEpochSecond(created).toString();
        String params = "(\"@method\" \"@authority\" \"@path\" \"signature-date\" \"content-digest\" \"content-type\")"
                + ";alg=\"ecdsa-p256-sha256\";keyid=\"" + keyId + "\";created=" + created + ";expires=" + expires;
        String base = "\"@method\": " + METHOD + "\n"
                + "\"@authority\": " + AUTHORITY + "\n"
                + "\"@path\": " + PATH + "\n"
                + "\"signature-date\": " + date + "\n"
                + "\"content-digest\": " + digest + "\n"
                + "\"content-type\": application/json\n"
                + "\"@signature-params\": " + params;
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(keys.getPrivate());
        signer.update(base.getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(signer.sign());

        Map<String, String> h = new HashMap<>();
        h.put("content-type", "application/json");
        h.put("content-digest", digest);
        h.put("signature-date", date);
        h.put("signature-input", "sig-pp=" + params);
        h.put("signature", "sig-pp=:" + sig + ":");
        return h;
    }

    private static PawapaySignatureVerifier verifier() {
        return new PawapaySignatureVerifier(keyId -> KEY_ID.equals(keyId) ? Optional.of(keys.getPublic()) : Optional.empty());
    }

    @Test
    void validSignature_passes() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = signedHeaders(BODY, now, now + 60, KEY_ID);
        assertThatCode(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY)).doesNotThrowAnyException();
    }

    @Test
    void tamperedBody_failsOnDigest() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = signedHeaders(BODY, now, now + 60, KEY_ID);
        byte[] other = "{\"depositId\":\"abc\",\"status\":\"FAILED\"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, other))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("Content-Digest");
    }

    @Test
    void differentPath_failsOnSignature() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = signedHeaders(BODY, now, now + 60, KEY_ID);
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, "/api/v1/pawapay/callbacks/payouts", h, BODY))
                .isInstanceOf(PawapaySignatureException.class);
    }

    @Test
    void unknownKey_fails() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = signedHeaders(BODY, now, now + 60, "OTHER:9");
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("keyid");
    }

    @Test
    void expiredSignature_fails() throws Exception {
        long past = Instant.now().getEpochSecond() - 3600;
        Map<String, String> h = signedHeaders(BODY, past, past + 60, KEY_ID);
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("expir");
    }

    @Test
    void missingHeaders_fail() {
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, Map.of(), BODY))
                .isInstanceOf(PawapaySignatureException.class);
    }

    @Test
    void unsupportedAlgorithm_fails() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = new HashMap<>(signedHeaders(BODY, now, now + 60, KEY_ID));
        h.put("signature-input", h.get("signature-input").replace("ecdsa-p256-sha256", "hmac-sha256"));
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("alg");
    }
}
