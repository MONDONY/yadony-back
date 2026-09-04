package com.yadony.api.payments.pawapay;

import com.yadony.api.payments.pawapay.dto.PawapayPublicKey;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Résout un {@code keyid} de callback en clé publique (PEM SPKI, EC ou RSA), avec un rafraîchissement si inconnu. */
@Component
public class PawapayPublicKeyStore implements PawapaySignatureVerifier.KeyResolver {

    private static final Logger log = LoggerFactory.getLogger(PawapayPublicKeyStore.class);
    private final PawapayClient client;

    public PawapayPublicKeyStore(PawapayClient client) {
        this.client = client;
    }

    @Override
    public Optional<PublicKey> resolve(String keyId) {
        Optional<PublicKey> found = lookup(keyId);
        if (found.isPresent()) return found;
        client.evictCaches();
        return lookup(keyId);
    }

    private Optional<PublicKey> lookup(String keyId) {
        try {
            for (PawapayPublicKey k : client.publicKeys()) {
                if (keyId.equals(k.id())) return Optional.of(parsePem(k.pem()));
            }
        } catch (Exception e) {
            log.error("pawaPay : clés publiques indisponibles ({})", e.toString());
        }
        return Optional.empty();
    }

    static PublicKey parsePem(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("EC").generatePublic(spec);
        } catch (Exception ec) {
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        }
    }
}
