package com.yadony.api.payments.pawapay;

import com.yadony.api.payments.pawapay.dto.PawapayPublicKey;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Résout un {@code keyid} de callback en clé publique (PEM SPKI, EC ou RSA), avec un
 * rafraîchissement si inconnu.
 *
 * <p>Le rafraîchissement ({@link PawapayClient#evictCaches()}) est limité à une fois par
 * fenêtre de 60 secondes. Il est déclenché par un {@code keyid} entièrement choisi par
 * l'appelant, avant toute vérification cryptographique, et coûte deux appels sortants vers
 * pawaPay : sans cette limite, un flot de callbacks à {@code keyid} inconnu pourrait épuiser le
 * quota pawaPay, faisant tomber en 401 des callbacks légitimes — le rail cesserait alors de
 * confirmer les paiements. Une rotation de clé réelle reste rattrapée au pire en 60 s, ce qui est
 * sans effet puisque pawaPay réessaie ses callbacks non acquittés.
 *
 * <p>Les clés parsées sont mémorisées par contenu PEM : décoder le Base64 et interroger la
 * {@code KeyFactory} à chaque callback (plusieurs par dépôt) est une fonction pure d'une donnée
 * que {@link PawapayClient} cache déjà une heure. Vidée en même temps que ce cache ; une clé au
 * contenu changé est une entrée différente, jamais servie depuis l'ancienne.
 */
@Component
public class PawapayPublicKeyStore implements PawapaySignatureVerifier.KeyResolver {

    private static final Logger log = LoggerFactory.getLogger(PawapayPublicKeyStore.class);
    private static final Duration EVICTION_MIN_INTERVAL = Duration.ofSeconds(60);

    private final PawapayClient client;
    private final Clock clock;
    private final Map<String, PublicKey> parsedByPem = new ConcurrentHashMap<>();
    private Instant lastEvictionAt = Instant.MIN;

    @Autowired
    public PawapayPublicKeyStore(PawapayClient client) {
        this(client, Clock.systemUTC());
    }

    PawapayPublicKeyStore(PawapayClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    @Override
    public Optional<PublicKey> resolve(String keyId) {
        Optional<PublicKey> found = lookup(keyId);
        if (found.isPresent()) return found;
        if (!allowEviction()) return Optional.empty();
        client.evictCaches();
        parsedByPem.clear();
        return lookup(keyId);
    }

    /** Autorise un rafraîchissement si le précédent date d'au moins 60 s ; marque immédiatement (anti-rafale). */
    private synchronized boolean allowEviction() {
        Instant now = clock.instant();
        if (Duration.between(lastEvictionAt, now).compareTo(EVICTION_MIN_INTERVAL) < 0) {
            return false;
        }
        lastEvictionAt = now;
        return true;
    }

    private Optional<PublicKey> lookup(String keyId) {
        try {
            for (PawapayPublicKey k : client.publicKeys()) {
                if (keyId.equals(k.id())) return Optional.of(parsed(k.pem()));
            }
        } catch (Exception e) {
            log.error("pawaPay : clés publiques indisponibles ({})", e.toString());
        }
        return Optional.empty();
    }

    private PublicKey parsed(String pem) throws Exception {
        PublicKey cached = parsedByPem.get(pem);
        if (cached != null) return cached;
        PublicKey key = parsePem(pem);
        parsedByPem.put(pem, key);
        return key;
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
