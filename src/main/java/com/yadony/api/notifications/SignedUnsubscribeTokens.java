package com.yadony.api.notifications;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Jeton signé d'un lien de désinscription en un clic, commun aux e-mails de suggestion de trajets
 * et aux relances de vérification d'identité.
 *
 * <p>Format : {@code base64url(userId:expiration).base64url(HMAC-SHA256)}, sans état en base.
 * Comme {@code StripeOnboardingResumeTokens}, la clé est dérivée de {@code app.encryption.key}
 * (aucun secret de plus à poser en prod), avec {@code SHA-256(purpose + "|" + clé)} : chaque
 * finalité a sa propre clé. Pour {@code "search-suggestion-unsubscribe"}, la dérivation est
 * exactement celle d'avant la mise en commun, les liens déjà envoyés restent valables.
 *
 * <p>Validité longue ({@link #VALIDITY}) : un lien de désinscription doit encore marcher quand
 * on retrouve l'e-mail des semaines plus tard. Le jeton ne fait que couper un réglage.
 */
public class SignedUnsubscribeTokens {

    static final Duration VALIDITY = Duration.ofDays(365);

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final Clock clock;

    /**
     * @param purpose       finalité du jeton : elle entre dans la dérivation de la clé, donc un jeton
     *                      émis pour une finalité n'est jamais valable pour une autre
     * @param encryptionKey {@code app.encryption.key}
     */
    public SignedUnsubscribeTokens(String purpose, String encryptionKey, Clock clock) {
        this.key = derive(purpose, encryptionKey);
        this.clock = clock;
    }

    public String issue(UUID userId) {
        long expiresAt = Instant.now(clock).plus(VALIDITY).getEpochSecond();
        String payload = userId + ":" + expiresAt;
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(sign(payload));
    }

    /** Utilisateur du jeton, ou vide si le jeton est absent, falsifié ou expiré. */
    public Optional<UUID> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            String payload = new String(DECODER.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] signature = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sign(payload), signature)) {
                return Optional.empty();
            }
            int colon = payload.indexOf(':');
            if (colon <= 0) {
                return Optional.empty();
            }
            long expiresAt = Long.parseLong(payload.substring(colon + 1));
            if (Instant.now(clock).getEpochSecond() >= expiresAt) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(payload.substring(0, colon)));
        } catch (IllegalArgumentException e) {
            // Base64 invalide, nombre ou UUID illisible : jeton bricolé.
            return Optional.empty();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    private static byte[] derive(String purpose, String encryptionKey) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((purpose + "|" + encryptionKey).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
