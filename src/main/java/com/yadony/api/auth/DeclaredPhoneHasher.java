package com.yadony.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(DeclaredPhoneHasher.class);

    /** Nul quand la clé est absente : la fonction « numéro déclaré » est alors désactivée. */
    private final SecretKeySpec key;

    public DeclaredPhoneHasher(@Value("${yadony.declared-phone.hash-key:}") String hashKey) {
        if (hashKey == null || hashKey.isBlank()) {
            this.key = null;
            log.warn("DECLARED_PHONE_HASH_KEY absente : numéro déclaré sans SMS désactivé");
        } else {
            this.key = new SecretKeySpec(hashKey.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        }
    }

    /** Faux si la clé est absente ou blanche : l'appelant doit alors désactiver la fonction. */
    public boolean isConfigured() {
        return key != null;
    }

    /** HMAC-SHA256 en hexadécimal (64 caractères) du numéro E.164. */
    public String hash(String e164) {
        if (key == null) {
            throw new IllegalStateException(
                    "numéro déclaré désactivé : DECLARED_PHONE_HASH_KEY n'est pas configurée");
        }
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
