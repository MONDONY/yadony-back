package com.yadony.api.payments;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * Jeton du lien « Terminer mon inscription » des e-mails de relance Stripe Connect.
 *
 * <p>Un lien Stripe (AccountLink) expire en quelques minutes : impossible de le mettre
 * dans un e-mail lu le lendemain. L'e-mail porte donc ce jeton, que
 * {@link StripeOnboardingResumeController} échange contre un lien Stripe neuf au clic.
 *
 * <p>Format : {@code base64url(userId:expiration).base64url(HMAC-SHA256)}. Aucun état en
 * base : le jeton se vérifie seul, et cesse de servir à son expiration ou dès que
 * l'onboarding est terminé (vérifié par le contrôleur). La clé HMAC est dérivée de
 * {@code app.encryption.key}, déjà présente partout, pour ne pas ajouter un secret à
 * poser en prod ; le préfixe de dérivation la sépare de l'usage chiffrement.
 */
@Component
public class StripeOnboardingResumeTokens {

    /** Assez long pour un e-mail ouvert en retard, la seconde relance arrive une semaine après. */
    static final Duration VALIDITY = Duration.ofDays(14);

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DERIVATION_PREFIX = "stripe-onboarding-resume|";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final Clock clock;

    @Autowired
    public StripeOnboardingResumeTokens(@Value("${app.encryption.key}") String encryptionKey) {
        this(encryptionKey, Clock.systemUTC());
    }

    StripeOnboardingResumeTokens(String encryptionKey, Clock clock) {
        this.key = derive(encryptionKey);
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

    private static byte[] derive(String encryptionKey) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((DERIVATION_PREFIX + encryptionKey).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
