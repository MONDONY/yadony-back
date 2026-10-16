package com.yadony.api.assistant.llm;

import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
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

    private static InetAddress v6(int... bytes) throws UnknownHostException {
        byte[] raw = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            raw[16 - bytes.length + i] = (byte) bytes[i];
        }
        return InetAddress.getByAddress(raw);
    }

    @Test
    void refuseLesVraiesAdressesIpv6EmbarquantUneIpv4Privee() throws Exception {
        InetAddress compatible = v6(0x7f, 0x00, 0x00, 0x01);                      // ::7f00:1
        InetAddress nat64 = InetAddress.getByAddress(new byte[]{0x00, 0x64, (byte) 0xff, (byte) 0x9b,
                0, 0, 0, 0, 0, 0, 0, 0, 0x7f, 0x00, 0x00, 0x01});                 // 64:ff9b::7f00:1
        InetAddress mappee = InetAddress.getByAddress(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xff, (byte) 0xff, 10, 0, 0, 1});
        assertThat(compatible).isInstanceOf(Inet6Address.class);
        assertThat(nat64).isInstanceOf(Inet6Address.class);
        for (InetAddress a : new InetAddress[]{compatible, nat64, mappee}) {
            ProviderAddressGuard guard = new ProviderAddressGuard(host -> new InetAddress[]{a});
            assertThatThrownBy(() -> guard.check("https://api.example.com/v1"))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            e -> assertThat(e.status()).isEqualTo(LlmCallException.BLOCKED_ADDRESS));
        }
    }

    @Test
    void accepteUneIpv6Nat64VersIpv4Publique() throws Exception {
        InetAddress nat64Public = InetAddress.getByAddress(new byte[]{0x00, 0x64, (byte) 0xff, (byte) 0x9b,
                0, 0, 0, 0, 0, 0, 0, 0, 0x08, 0x08, 0x08, 0x08});                 // 64:ff9b::808:808
        assertThat(nat64Public).isInstanceOf(Inet6Address.class);
        assertThatCode(() -> new ProviderAddressGuard(host -> new InetAddress[]{nat64Public})
                .check("https://api.example.com/v1")).doesNotThrowAnyException();
    }

    @Test
    void leResolveurHttpRendExactementLesAdressesVerifiees() throws Exception {
        InetAddress[] publics = literal("8.8.8.8", "2606:4700:4700::1111");
        DnsResolver dns = new ProviderAddressGuard(host -> publics).asDnsResolver();
        assertThat(dns.resolve("api.example.com")).containsExactly(publics);
        assertThat(dns.resolveCanonicalHostname("api.example.com")).isEqualTo("api.example.com");
    }

    @Test
    void leResolveurHttpRefuseSiUneSeuleAdresseEstPrivee() throws Exception {
        DnsResolver dns = resolvingTo("8.8.8.8", "127.0.0.1").asDnsResolver();
        assertThatThrownBy(() -> dns.resolve("api.example.com"))
                .isInstanceOf(ProviderAddressGuard.BlockedAddressException.class)
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void leResolveurHttpPropageUnHoteInconnuSansLeConfondreAvecUnRefus() {
        DnsResolver dns = new ProviderAddressGuard(host -> { throw new UnknownHostException(host); }).asDnsResolver();
        assertThatThrownBy(() -> dns.resolve("nope.invalid"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(ProviderAddressGuard.BlockedAddressException.class);
    }
}
