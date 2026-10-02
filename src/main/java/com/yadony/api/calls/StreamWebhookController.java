package com.yadony.api.calls;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Webhook Stream Video. Public, authentifié par signature HMAC du corps brut. */
@RestController
public class StreamWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StreamWebhookController.class);
    /** Un événement Stream fait quelques Ko ; au-delà, refus (bombe de décompression). */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final StreamWebhookVerifier verifier;
    private final CallWebhookService service;
    private final ObjectMapper objectMapper;

    public StreamWebhookController(StreamWebhookVerifier verifier, CallWebhookService service, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/calls/webhook")
    public ResponseEntity<Void> receive(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-Signature", required = false) String signature,
                                        @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                        @RequestHeader(value = "Content-Encoding", required = false) String encoding)
            throws IOException {
        // Stream compresse ses webhooks (gzip) et signe le JSON décompressé : la
        // signature se vérifie sur le corps décompressé, jamais sur les octets reçus.
        byte[] json = isGzip(encoding, body) ? gunzip(body) : body;
        if (json == null || !verifier.verify(json, signature, apiKey)) {
            log.warn("Webhook Stream refusé (signature {}, encodage {}, {} octets)",
                    signature == null ? "absente" : "invalide", encoding, body == null ? 0 : body.length);
            return ResponseEntity.status(401).build();
        }
        service.handle(objectMapper.readTree(json));
        return ResponseEntity.ok().build();
    }

    private static boolean isGzip(String encoding, byte[] body) {
        if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) return true;
        // Octets magiques gzip, au cas où l'en-tête serait retiré par un proxy.
        return body != null && body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
    }

    /** Corps décompressé, ou null s'il est illisible ou trop gros. */
    private static byte[] gunzip(byte[] body) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_BODY_BYTES) return null;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
