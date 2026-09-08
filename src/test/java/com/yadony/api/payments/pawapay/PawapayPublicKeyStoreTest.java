package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.payments.pawapay.dto.PawapayPublicKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

/**
 * {@link PawapayPublicKeyStore} choisit la clé publique qui valide la signature d'un callback
 * pawaPay — donc, in fine, la confirmation d'un paiement. Son comportement de cache doit être
 * épinglé : {@link #keyFoundOnFirstLookup_neverEvictsCache()} est le test le plus important de
 * cette classe — si le store vidait le cache (1 h) de {@link PawapayClient#publicKeys()} à
 * chaque callback, yadony martèlerait l'API pawaPay à chaque notification reçue.
 *
 * <p>Ronde 2 (point 3) : le rafraîchissement est en plus limité à une fois par fenêtre de 60 s,
 * horodatée via une {@link Clock} injectable — {@link MutableClock} pilote le temps sans
 * dépendre de l'horloge murale.
 */
@ExtendWith(MockitoExtension.class)
class PawapayPublicKeyStoreTest {

    private static final String KEY_ID = "HTTP_EC_P256_KEY:1";
    private static KeyPair ecKeys;

    @Mock
    private PawapayClient client;

    private PawapayPublicKeyStore store;

    @BeforeAll
    static void generateEcKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        ecKeys = gen.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        store = new PawapayPublicKeyStore(client);
    }

    /** Encode une clé publique en PEM SPKI, comme le renvoie {@code GET /v2/public-key/http}. */
    private static String pem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static PawapayPublicKey knownEcKey() {
        return new PawapayPublicKey(KEY_ID, pem(ecKeys.getPublic()));
    }

    /** Horloge de test dont l'instant peut être avancé manuellement pour piloter la fenêtre d'éviction. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void keyFoundOnFirstLookup_neverEvictsCache() {
        when(client.publicKeys()).thenReturn(List.of(knownEcKey()));

        Optional<PublicKey> result = store.resolve(KEY_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getEncoded()).isEqualTo(ecKeys.getPublic().getEncoded());
        verify(client, never()).evictCaches();
        verify(client, times(1)).publicKeys();
    }

    @Test
    void keyMissingThenFoundAfterRotation_evictsCacheExactlyOnce() {
        when(client.publicKeys()).thenReturn(List.of(), List.of(knownEcKey()));

        Optional<PublicKey> result = store.resolve(KEY_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getEncoded()).isEqualTo(ecKeys.getPublic().getEncoded());
        verify(client, times(1)).evictCaches();
        verify(client, times(2)).publicKeys();
    }

    @Test
    void keyMissingAfterRotationToo_returnsEmptyWithoutLooping() {
        // Horloge explicite : rend visible la précondition « premier rafraîchissement, donc autorisé »
        // plutôt que de dépendre implicitement du sentinel Instant.MIN d'un store neuf.
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PawapayPublicKeyStore clockedStore = new PawapayPublicKeyStore(client, clock);
        when(client.publicKeys()).thenReturn(List.of());

        Optional<PublicKey> result = clockedStore.resolve("UNKNOWN:9");

        assertThat(result).isEmpty();
        verify(client, times(1)).evictCaches();
        verify(client, times(2)).publicKeys();
    }

    @Test
    void secondUnknownKeyWithinWindow_doesNotEvictAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PawapayPublicKeyStore clockedStore = new PawapayPublicKeyStore(client, clock);
        when(client.publicKeys()).thenReturn(List.of());

        Optional<PublicKey> first = clockedStore.resolve("UNKNOWN:1");
        // Même horloge (aucun temps écoulé), keyId différent : la fenêtre de 60 s n'est pas
        // encore ouverte, la deuxième éviction doit être refusée.
        Optional<PublicKey> second = clockedStore.resolve("UNKNOWN:2");

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        verify(client, times(1)).evictCaches();
        verify(client, times(3)).publicKeys();
    }

    @Test
    void evictionAllowedAgain_afterWindowElapses() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PawapayPublicKeyStore clockedStore = new PawapayPublicKeyStore(client, clock);
        when(client.publicKeys()).thenReturn(List.of());

        clockedStore.resolve("UNKNOWN:1");
        clock.advance(Duration.ofSeconds(61));
        clockedStore.resolve("UNKNOWN:3");

        verify(client, times(2)).evictCaches();
        verify(client, times(4)).publicKeys();
    }

    @Test
    void publicKeysUnavailable_failsClosedWithoutThrowing() {
        when(client.publicKeys()).thenThrow(new RestClientException("pawaPay indisponible"));

        Optional<PublicKey> result = store.resolve(KEY_ID);

        assertThat(result).isEmpty();
        verify(client, times(1)).evictCaches();
    }

    @Test
    void ecPem_isParsedAndReturned() {
        when(client.publicKeys()).thenReturn(List.of(knownEcKey()));

        Optional<PublicKey> result = store.resolve(KEY_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getAlgorithm()).isEqualTo("EC");
        assertThat(result.get().getEncoded()).isEqualTo(ecKeys.getPublic().getEncoded());
    }

    @Test
    void rsaPem_isParsedAndReturned() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair rsaKeys = gen.generateKeyPair();
        when(client.publicKeys()).thenReturn(List.of(new PawapayPublicKey("RSA_KEY:1", pem(rsaKeys.getPublic()))));

        Optional<PublicKey> result = store.resolve("RSA_KEY:1");

        assertThat(result).isPresent();
        assertThat(result.get().getAlgorithm()).isEqualTo("RSA");
        assertThat(result.get().getEncoded()).isEqualTo(rsaKeys.getPublic().getEncoded());
    }

    @Test
    void corruptedPem_returnsEmptyWithoutThrowing() {
        PawapayPublicKey corrupted = new PawapayPublicKey(KEY_ID,
                "-----BEGIN PUBLIC KEY-----\nNOT-VALID-BASE64-!!!\n-----END PUBLIC KEY-----");
        when(client.publicKeys()).thenReturn(List.of(corrupted));

        Optional<PublicKey> result = store.resolve(KEY_ID);

        assertThat(result).isEmpty();
    }
}
