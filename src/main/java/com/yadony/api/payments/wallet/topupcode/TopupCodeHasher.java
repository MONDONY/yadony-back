package com.yadony.api.payments.wallet.topupcode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/**
 * Empreinte des codes de recharge : HMAC-SHA256 du code normalisé, avec une clé secrète hors
 * base. La table ne contient plus aucun code utilisable : une fuite de la base, d'une sauvegarde
 * ou d'un export ne permet pas de créditer un solde. Une empreinte sans clé (SHA-256 nu) ne
 * suffirait pas : les codes sont courts et leur alphabet connu, donc énumérables hors ligne.
 *
 * <p>Changer la clé rend tous les codes en cours inutilisables.
 */
@Component
public class TopupCodeHasher {

    private static final Logger log = LoggerFactory.getLogger(TopupCodeHasher.class);

    /** Profils où une clé de repli est tolérée : poste local et tests. */
    static final Set<String> FALLBACK_KEY_PROFILES = Set.of("dev", "test", "e2e");
    static final String FALLBACK_KEY = "yadony-dev-only-topup-code-hmac-key";
    static final int MIN_KEY_LENGTH = 32;
    static final int HINT_LENGTH = 4;
    static final int SHORT_HINT_LENGTH = 2;
    static final int LONG_CODE_LENGTH = 16;

    private final SecretKeySpec key;

    @Autowired
    public TopupCodeHasher(@Value("${yadony.wallet.topup-code.hmac-key:}") String configuredKey,
                           Environment environment) {
        this(configuredKey, environment.getActiveProfiles());
    }

    TopupCodeHasher(String configuredKey, String... activeProfiles) {
        String raw = configuredKey == null ? "" : configuredKey.trim();
        if (raw.isEmpty()) {
            boolean tolerated = Arrays.stream(activeProfiles).anyMatch(FALLBACK_KEY_PROFILES::contains);
            if (!tolerated) {
                throw new IllegalStateException("TOPUP_CODE_HMAC_KEY est obligatoire hors dev/test (profils actifs : "
                        + String.join(",", activeProfiles) + ")");
            }
            log.warn("TOPUP_CODE_HMAC_KEY absente : clé de développement utilisée pour les codes de recharge");
            raw = FALLBACK_KEY;
        } else if (raw.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("TOPUP_CODE_HMAC_KEY trop courte : " + MIN_KEY_LENGTH
                    + " caractères au moins (openssl rand -hex 32)");
        }
        this.key = new SecretKeySpec(raw.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Empreinte hexadécimale (64 caractères) d'un code DÉJÀ normalisé. */
    public String hash(String normalizedCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 indisponible", e);
        }
    }

    /**
     * Fin du code, seule partie montrée après la création (liste admin, audit) : 4 caractères
     * pour un code d'au moins 16 caractères (codes générés), 2 en dessous, pour qu'un ancien
     * code court ne soit jamais révélé en entier ni réduit à quelques essais.
     */
    public static String hint(String normalizedCode) {
        int length = normalizedCode.length() >= LONG_CODE_LENGTH ? HINT_LENGTH : SHORT_HINT_LENGTH;
        return normalizedCode.substring(normalizedCode.length() - Math.min(length, normalizedCode.length()));
    }
}
