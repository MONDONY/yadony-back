package com.yadony.api.calls;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Signature des webhooks Stream : HMAC-SHA256 hexadécimal du corps brut, secret API. Fail-closed. */
@Component
public class StreamWebhookVerifier {

    private final StreamProperties properties;

    public StreamWebhookVerifier(StreamProperties properties) {
        this.properties = properties;
    }

    public boolean verify(byte[] rawBody, String signatureHeader, String apiKeyHeader) {
        String secret = properties.apiSecret();
        if (secret == null || secret.isBlank() || signatureHeader == null || rawBody == null) return false;
        if (apiKeyHeader != null && !apiKeyHeader.equals(properties.apiKey())) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
