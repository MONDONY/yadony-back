package com.yadony.api.assistant.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderAddressGuardTest {

    /** Faux DNS : l'hôte est lui-même un littéral IP (jamais de résolution réseau). */
    private static InetAddress[] literal(String... ips) throws UnknownHostException {
        InetAddress[] out = new InetAddress[ips.length];
        for (int i = 0; i < ips.length; i++) {
            out[i] = InetAddress.getByName(ips[i]);
        }
        return out;
    }

    private static ProviderAddressGuard resolvingTo(String... ips) {
        return new ProviderAddressGuard(host -> literal(ips));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.8.9.10", "::1",
            "0.0.0.0", "::",
            "169.254.169.254", "fe80::1",
            "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.1",
            "224.0.0.1", "ff02::1",
            "fc00::1", "fd12:3456:789a::1",
            "100.64.0.1", "100.127.255.254",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:192.168.0.1", "::ffff:169.254.169.254",
            "::ffff:100.64.0.1", "::ffff:0.0.0.0"
    })
    void refuseLesAdressesNonPubliques(String ip) {
        assertThatThrownBy(() -> resolvingTo(ip).check("https://api.example.com/v1"))
                .isInstanceOfSatisfying(LlmCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(LlmCallException.BLOCKED_ADDRESS);
                    assertThat(e.getMessage()).isEqualTo("Adresse du fournisseur refusée");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "100.63.255.255", "100.128.0.1",
            "2606:4700:4700::1111", "2001:4860:4860::8888"})
    void accepteLesAdressesPubliques(String ip) {
        assertThatCode(() -> resolvingTo(ip).check("https://api.example.com/v1")).doesNotThrowAnyException();
    }

    @Test
    void refuseSiUneSeuleAdresseEstPrivee() {
        assertThatThrownBy(() -> resolvingTo("8.8.8.8", "10.0.0.1").check("https://api.example.com/v1"))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.BLOCKED_ADDRESS));
    }

    @Test
    void hoteInconnuDonneNetwork() {
        ProviderAddressGuard guard = new ProviderAddressGuard(host -> { throw new UnknownHostException(host); });
        assertThatThrownBy(() -> guard.check("https://nope.invalid/v1"))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.NETWORK));
    }

    @Test
    void urlSansHoteOuIllisibleDonneNetwork() {
        ProviderAddressGuard guard = resolvingTo("8.8.8.8");
        assertThatThrownBy(() -> guard.check("pas une url"))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.NETWORK));
        assertThatThrownBy(() -> guard.check(null))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.NETWORK));
        assertThatThrownBy(() -> guard.check("file:///etc/passwd"))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.NETWORK));
    }

    @Test
    void lHoteResoluEstCeluiDeLUrl() {
        String[] seen = new String[1];
        ProviderAddressGuard guard = new ProviderAddressGuard(host -> { seen[0] = host; return literal("8.8.8.8"); });
        guard.check("https://api.groq.com:443/openai/v1");
        assertThat(seen[0]).isEqualTo("api.groq.com");
    }

    @Test
    void leConstructeurParDefautUtiliseLeDnsSysteme() {
        // Littéral IP : aucune requête DNS réelle, la résolution système rend l'adresse telle quelle.
        assertThatThrownBy(() -> new ProviderAddressGuard().check("http://127.0.0.1:9/v1"))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.BLOCKED_ADDRESS));
    }
}
