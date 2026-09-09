package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Variante de {@link #signedHeaders} qui expose directement la liste des composants couverts
     * et le suffixe de paramètres bruts — pour tester couverture réduite, {@code created}
     * absent, ou les espaces RFC 8941 après {@code ;}. Les en-têtes non dérivés éventuellement
     * référencés par {@code componentNames} prennent des valeurs par défaut cohérentes (digest
     * du corps réel, horodatage courant, JSON).
     */
    private static Map<String, String> signedHeadersCustom(byte[] body, List<String> componentNames, String paramsSuffix) throws Exception {
        String digest = "sha-512=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(body)) + ":";
        String date = Instant.now().toString();
        Map<String, String> headerValues = new HashMap<>();
        headerValues.put("content-type", "application/json");
        headerValues.put("content-digest", digest);
        headerValues.put("signature-date", date);

        StringBuilder componentsList = new StringBuilder();
        for (int i = 0; i < componentNames.size(); i++) {
            if (i > 0) componentsList.append(' ');
            componentsList.append('"').append(componentNames.get(i)).append('"');
        }
        String params = "(" + componentsList + ")" + paramsSuffix;

        StringBuilder base = new StringBuilder();
        for (String c : componentNames) {
            String value = switch (c) {
                case "@method" -> METHOD;
                case "@authority" -> AUTHORITY;
                case "@path" -> PATH;
                default -> headerValues.get(c);
            };
            base.append('"').append(c).append("\": ").append(value).append('\n');
        }
        base.append("\"@signature-params\": ").append(params);

        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(keys.getPrivate());
        signer.update(base.toString().getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(signer.sign());

        Map<String, String> h = new HashMap<>(headerValues);
        h.put("signature-input", "sig-pp=" + params);
        h.put("signature", "sig-pp=:" + sig + ":");
        return h;
    }

    private static PawapaySignatureVerifier verifier() {
        return new PawapaySignatureVerifier(keyId -> KEY_ID.equals(keyId) ? Optional.of(keys.getPublic()) : Optional.empty());
    }

    private static PawapaySignatureVerifier verifierWithClock(Clock clock) {
        return new PawapaySignatureVerifier(
                keyId -> KEY_ID.equals(keyId) ? Optional.of(keys.getPublic()) : Optional.empty(), clock);
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
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("invalide");
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
        // created reste dans la fenêtre de validité (< 300 s) : seul expires doit motiver le refus.
        long created = Instant.now().getEpochSecond() - 200;
        Map<String, String> h = signedHeaders(BODY, created, created + 60, KEY_ID);
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

    // ── Ronde 2 - point 1 : couverture minimale obligatoire (RFC 9421 §3.2) ────────────────────

    @Test
    void insufficientCoverage_onlyMethodCovered_fails() throws Exception {
        long now = Instant.now().getEpochSecond();
        String suffix = ";alg=\"ecdsa-p256-sha256\";keyid=\"" + KEY_ID + "\";created=" + now + ";expires=" + (now + 60);
        Map<String, String> h = signedHeadersCustom(BODY, List.of("@method"), suffix);
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("couverture");
    }

    // ── Ronde 2 - point 2 : trois 500 non authentifiés → doivent devenir des 401 ────────────────

    @Test
    void contentDigestWithEmptyByteSequence_isRejectedNotCrashed() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = new HashMap<>(signedHeaders(BODY, now, now + 60, KEY_ID));
        h.put("content-digest", "sha-512=:");
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("Content-Digest");
    }

    @Test
    void signatureWithEmptyByteSequence_isRejectedNotCrashed() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = new HashMap<>(signedHeaders(BODY, now, now + 60, KEY_ID));
        h.put("signature", "sig-pp=:");
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("Signature");
    }

    @Test
    void expiresNonNumeric_isRejectedNotCrashed() throws Exception {
        // "x" doit être ENTRE GUILLEMETS : la regex PARAM n'accepte après '=' qu'une chaîne
        // quotée ou des chiffres nus — un "expires=x" non quoté ne matche PARAM à aucune
        // position, param(...) renvoie null et parseEpochSeconds n'est alors jamais atteint (le
        // test échouerait uniquement sur la signature invalidée par la mutation, pas sur le
        // garde-fou testé ici).
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = new HashMap<>(signedHeaders(BODY, now, now + 60, KEY_ID));
        h.put("signature-input", h.get("signature-input").replace("expires=" + (now + 60), "expires=\"x\""));
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("expires");
    }

    @Test
    void expiresOverflowsLong_isRejectedNotCrashed() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = new HashMap<>(signedHeaders(BODY, now, now + 60, KEY_ID));
        h.put("signature-input", h.get("signature-input").replace("expires=" + (now + 60), "expires=99999999999999999999"));
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class);
    }

    // ── Ronde 2 - point 4 : created obligatoire et borné (anti-rejeu) ───────────────────────────

    @Test
    void missingCreated_fails() throws Exception {
        long now = Instant.now().getEpochSecond();
        List<String> components = List.of("@method", "@authority", "@path", "signature-date", "content-digest", "content-type");
        String suffix = ";alg=\"ecdsa-p256-sha256\";keyid=\"" + KEY_ID + "\";expires=" + (now + 60);
        Map<String, String> h = signedHeadersCustom(BODY, components, suffix);
        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("created");
    }

    @Test
    void createdTooOld_fails() throws Exception {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        long created = fixedNow.getEpochSecond() - 301;
        Map<String, String> h = signedHeaders(BODY, created, created + 60, KEY_ID);
        assertThatThrownBy(() -> verifierWithClock(clock).verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("ancien");
    }

    @Test
    void createdTooFarInFuture_fails() throws Exception {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        long created = fixedNow.getEpochSecond() + 61;
        Map<String, String> h = signedHeaders(BODY, created, created + 60, KEY_ID);
        assertThatThrownBy(() -> verifierWithClock(clock).verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class).hasMessageContaining("futur");
    }

    @Test
    void createdWithinBounds_passes() throws Exception {
        Instant fixedNow = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        long created = fixedNow.getEpochSecond() - 100;
        Map<String, String> h = signedHeaders(BODY, created, created + 60, KEY_ID);
        assertThatCode(() -> verifierWithClock(clock).verify(METHOD, AUTHORITY, PATH, h, BODY)).doesNotThrowAnyException();
    }

    // ── Ronde 2 - point 5 : whitelist alg appliquée avant toute résolution de clé ───────────────

    @Test
    void unsupportedAlgorithm_neverConsultsKeyResolver() throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = new HashMap<>(signedHeaders(BODY, now, now + 60, KEY_ID));
        h.put("signature-input", h.get("signature-input").replace("ecdsa-p256-sha256", "hmac-sha256"));
        AtomicBoolean resolverCalled = new AtomicBoolean(false);
        PawapaySignatureVerifier v = new PawapaySignatureVerifier(keyId -> {
            resolverCalled.set(true);
            return Optional.of(keys.getPublic());
        });
        assertThatThrownBy(() -> v.verify(METHOD, AUTHORITY, PATH, h, BODY)).isInstanceOf(PawapaySignatureException.class);
        assertThat(resolverCalled.get()).isFalse();
    }

    // ── Ronde 2 - point 6 : espace optionnel après ';' dans les paramètres (RFC 8941 §4.1.1.2) ──

    @Test
    void spacedParams_afterSemicolon_areAccepted() throws Exception {
        long now = Instant.now().getEpochSecond();
        List<String> components = List.of("@method", "@authority", "@path", "signature-date", "content-digest", "content-type");
        String suffix = "; alg=\"ecdsa-p256-sha256\"; keyid=\"" + KEY_ID + "\"; created=" + now + "; expires=" + (now + 60);
        Map<String, String> h = signedHeadersCustom(BODY, components, suffix);
        assertThatCode(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY)).doesNotThrowAnyException();
    }

    // ── Ronde 2 - point 7 : entrée non authentifiée tronquée à 64 caractères dans le detail ─────

    @Test
    void unknownKey_longKeyId_truncatedTo64CharsInMessage() throws Exception {
        long now = Instant.now().getEpochSecond();
        String longKeyId = "K".repeat(200);
        Map<String, String> h = signedHeaders(BODY, now, now + 60, longKeyId);
        Throwable thrown = catchThrowable(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY));
        assertThat(thrown).isInstanceOf(PawapaySignatureException.class);
        assertThat(thrown.getMessage()).contains("K".repeat(64)).doesNotContain("K".repeat(65));
    }

    @Test
    void unsupportedAlgorithm_longValue_truncatedTo64CharsInMessage() throws Exception {
        long now = Instant.now().getEpochSecond();
        String longAlg = "a".repeat(200);
        Map<String, String> h = new HashMap<>(signedHeaders(BODY, now, now + 60, KEY_ID));
        h.put("signature-input", h.get("signature-input").replace("ecdsa-p256-sha256", longAlg));
        Throwable thrown = catchThrowable(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY));
        assertThat(thrown).isInstanceOf(PawapaySignatureException.class);
        assertThat(thrown.getMessage()).contains("a".repeat(64)).doesNotContain("a".repeat(65));
    }

    // ── Les quatre algorithmes de la fabrique signatureFor ──────────────────────────────────

    /**
     * Comme {@link #signedHeaders}, mais avec la paire de clés, l'{@code alg} RFC 9421, le
     * {@link Signature} JCA et l'algorithme de {@code Content-Digest} en paramètres : pawaPay
     * annonce quatre algorithmes, seul ECDSA P-256 est exercé par le reste de la classe.
     */
    private static Map<String, String> signedHeadersWith(KeyPair pair, String alg, Signature signer, String keyId,
                                                          String digestJca) throws Exception {
        String digestLabel = "SHA-256".equals(digestJca) ? "sha-256" : "sha-512";
        String digest = digestLabel + "=:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance(digestJca).digest(BODY)) + ":";
        long now = Instant.now().getEpochSecond();
        String date = Instant.ofEpochSecond(now).toString();
        String params = "(\"@method\" \"@authority\" \"@path\" \"signature-date\" \"content-digest\" \"content-type\")"
                + ";alg=\"" + alg + "\";keyid=\"" + keyId + "\";created=" + now + ";expires=" + (now + 60);
        String base = "\"@method\": " + METHOD + "\n"
                + "\"@authority\": " + AUTHORITY + "\n"
                + "\"@path\": " + PATH + "\n"
                + "\"signature-date\": " + date + "\n"
                + "\"content-digest\": " + digest + "\n"
                + "\"content-type\": application/json\n"
                + "\"@signature-params\": " + params;
        signer.initSign(pair.getPrivate());
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

    private static PawapaySignatureVerifier verifierFor(String keyId, KeyPair pair) {
        return new PawapaySignatureVerifier(id -> keyId.equals(id) ? Optional.of(pair.getPublic()) : Optional.empty());
    }

    @Test
    void ecdsaP384Signature_passes() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair pair = gen.generateKeyPair();
        Map<String, String> h = signedHeadersWith(pair, "ecdsa-p384-sha384",
                Signature.getInstance("SHA384withECDSAinP1363Format"), "HTTP_EC_P384_KEY:1", "SHA-512");

        assertThatCode(() -> verifierFor("HTTP_EC_P384_KEY:1", pair).verify(METHOD, AUTHORITY, PATH, h, BODY))
                .doesNotThrowAnyException();
    }

    /**
     * Recette staging du 2026-09-09 : pawaPay encode ses signatures ECDSA en DER (comme
     * l'exemple {@code sig-pp=:MEQCI…:} de sa documentation), jamais en {@code r || s} brut ;
     * la forme brute reste acceptée (tests {@code ecdsaP256Signature_*} ci-dessus).
     */
    @Test
    void ecdsaP256DerEncodedSignature_passes() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();
        Map<String, String> h = signedHeadersWith(pair, "ecdsa-p256-sha256",
                Signature.getInstance("SHA256withECDSA"), "HTTP_EC_P256_KEY:1", "SHA-512");

        assertThatCode(() -> verifierFor("HTTP_EC_P256_KEY:1", pair).verify(METHOD, AUTHORITY, PATH, h, BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void ecdsaP384DerEncodedSignature_passes() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp384r1"));
        KeyPair pair = gen.generateKeyPair();
        Map<String, String> h = signedHeadersWith(pair, "ecdsa-p384-sha384",
                Signature.getInstance("SHA384withECDSA"), "HTTP_EC_P384_KEY:1", "SHA-512");

        assertThatCode(() -> verifierFor("HTTP_EC_P384_KEY:1", pair).verify(METHOD, AUTHORITY, PATH, h, BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void ecdsaDerEncodedSignature_fromAnotherKey_isRejected() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair signer = gen.generateKeyPair();
        KeyPair other = gen.generateKeyPair();
        Map<String, String> h = signedHeadersWith(signer, "ecdsa-p256-sha256",
                Signature.getInstance("SHA256withECDSA"), "HTTP_EC_P256_KEY:1", "SHA-512");

        assertThatThrownBy(() -> verifierFor("HTTP_EC_P256_KEY:1", other).verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class)
                .hasMessage("Signature invalide");
    }

    @Test
    void rsaV15Signature_withSha256ContentDigest_passes() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        Map<String, String> h = signedHeadersWith(pair, "rsa-v1_5-sha256",
                Signature.getInstance("SHA256withRSA"), "HTTP_RSA_KEY:1", "SHA-256");

        assertThatCode(() -> verifierFor("HTTP_RSA_KEY:1", pair).verify(METHOD, AUTHORITY, PATH, h, BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void rsaPssSha512Signature_passes() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        Signature signer = Signature.getInstance("RSASSA-PSS");
        signer.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1));
        Map<String, String> h = signedHeadersWith(pair, "rsa-pss-sha512", signer, "HTTP_RSA_PSS_KEY:1", "SHA-512");

        assertThatCode(() -> verifierFor("HTTP_RSA_PSS_KEY:1", pair).verify(METHOD, AUTHORITY, PATH, h, BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void keyOfAnotherType_thanTheAnnouncedAlg_isRejected_notCrashed() throws Exception {
        // alg RSA annoncé, clé EC résolue : initVerify lève InvalidKeyException — traduite en
        // signature invalide (exception métier du vérifieur), jamais en 500.
        long now = Instant.now().getEpochSecond();
        Map<String, String> h = new HashMap<>(signedHeaders(BODY, now, now + 60, KEY_ID));
        h.put("signature-input", h.get("signature-input").replace("ecdsa-p256-sha256", "rsa-v1_5-sha256"));

        assertThatThrownBy(() -> verifier().verify(METHOD, AUTHORITY, PATH, h, BODY))
                .isInstanceOf(PawapaySignatureException.class);
    }
}
