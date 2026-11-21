package com.yadony.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Empreinte à clé (HMAC-SHA256) d'un numéro déclaré sans SMS.
 *
 * <p>Le numéro lui-même vit dans un claim Firebase, jamais en base : on n'y garde que cette
 * empreinte, pour retrouver les comptes qui déclarent un numéro donné. La clé est un secret
 * d'environnement ({@code DECLARED_PHONE_HASH_KEY}) : sans elle, un vol de la base ne permet pas
 * de parcourir l'espace des numéros pour retrouver les propriétaires.
 */
@Component
public class DeclaredPhoneHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public DeclaredPhoneHasher(@Value("${yadony.declared-phone.hash-key:}") String hashKey) {
        if (hashKey == null || hashKey.isBlank()) {
            throw new IllegalStateException(
                    "yadony.declared-phone.hash-key (DECLARED_PHONE_HASH_KEY) est obligatoire");
        }
        this.key = new SecretKeySpec(hashKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** HMAC-SHA256 en hexadécimal (64 caractères) du numéro E.164. */
    public String hash(String e164) {
        if (e164 == null || e164.isBlank()) {
            throw new IllegalArgumentException("numéro vide");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(e164.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }
}
