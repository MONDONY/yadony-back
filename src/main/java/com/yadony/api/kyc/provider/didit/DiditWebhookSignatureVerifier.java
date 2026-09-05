package com.yadony.api.kyc.provider.didit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifie l'en-tete {@code X-Signature} de Didit : HMAC-SHA256 hexadecimal des octets bruts du
 * corps, avec la cle partagee de la destination webhook.
 *
 * <p>Pourquoi pas {@code X-Signature-V2}, pourtant recommande par Didit : sa valeur porte sur
 * une forme canonique du JSON (cles triees recursivement, flottants tronques, Unicode non
 * echappe) dont la definition est celle de {@code json.dumps} en Python. La reproduire en Java
 * est une source classique de divergence silencieuse. V2 n'existe que pour les frameworks qui
 * reencodent le corps avant le handler ; ici le controleur lit {@code byte[]}, donc les octets
 * exacts, et le webhook Stripe deja en production sur la meme chaine nginx puis Spring prouve
 * que le corps brut arrive intact. A rejuger si un proxy reecrivant les corps est introduit.
 *
 * <p>Fail-closed : sans secret configure, aucun webhook n'est accepte. Un environnement mal
 * configure doit refuser les evenements, jamais les croire sur parole.
 */
@Component
public class DiditWebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(DiditWebhookSignatureVerifier.class);

    /** Fenetre anti-rejeu, en secondes — la valeur prescrite par Didit. */
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final DiditProperties properties;
    private final Clock clock;

    public DiditWebhookSignatureVerifier(DiditProperties properties) {
        this(properties, Clock.systemUTC());
    }

    DiditWebhookSignatureVerifier(DiditProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param rawBody         octets exacts recus, avant tout parsing
     * @param signatureHeader valeur de {@code X-Signature}
     * @param timestampHeader valeur de {@code X-Timestamp}, en secondes Unix
     */
    public boolean verify(byte[] rawBody, String signatureHeader, String timestampHeader) {
        String secret = properties.webhookSecret();
        if (secret == null || secret.isBlank()) {
            log.error("Webhook Didit reçu alors qu'aucun secret n'est configuré — rejeté");
            return false;
        }
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()
                || timestampHeader == null || timestampHeader.isBlank()) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
            log.warn("Webhook Didit hors de la fenêtre de {} s — rejeté", MAX_CLOCK_SKEW_SECONDS);
            return false;
        }

        String expected;
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (Exception e) {
            log.error("Calcul de signature Didit impossible ({})", e.getClass().getSimpleName());
            return false;
        }

        // Comparaison en temps constant : une comparaison naive laisse fuir la signature
        // attendue, octet par octet, par la duree de la reponse.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
