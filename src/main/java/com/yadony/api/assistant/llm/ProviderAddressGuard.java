package com.yadony.api.assistant.llm;

import org.apache.hc.client5.http.DnsResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Frontière anti-SSRF : l'URL de base d'un fournisseur est saisie par un administrateur, on refuse donc
 * de contacter tout hôte dont UNE adresse résolue n'est pas publique (boucle locale, réseaux privés,
 * lien local, métadonnées cloud 169.254.169.254, CGNAT, multicast, unique-local IPv6, IPv4 mappée en IPv6).
 *
 * <p>Rebinding DNS : {@link #check} échoue vite avec une erreur claire, mais la garantie vient de
 * {@link #asDnsResolver()}, que le client HTTP utilise pour sa propre connexion. Il n'y a ainsi qu'une
 * seule résolution qui compte, et la connexion s'établit sur les adresses qui viennent d'être vérifiées.
 * Une seule règle ({@code isNonPublic}) sert aux deux chemins.
 */
@Component
public class ProviderAddressGuard {

    /** Résolution DNS, injectable pour que les tests n'utilisent jamais le réseau. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    /** Seul constructeur annoté : Spring en voit un second (tests) et ne saurait pas lequel choisir. */
    @Autowired
    public ProviderAddressGuard() {
        this(InetAddress::getAllByName);
    }

    ProviderAddressGuard(HostResolver resolver) {
        this.resolver = resolver;
    }

    /** Levée par le résolveur gardé ; reconnue par le client pour répondre BLOCKED_ADDRESS. */
    static final class BlockedAddressException extends UnknownHostException {
        BlockedAddressException() {
            super("Adresse du fournisseur refusée");
        }
    }

    public void check(String baseUrl) {
        String host = hostOf(baseUrl);
        try {
            verifiedAddresses(host);
        } catch (BlockedAddressException e) {
            throw new LlmCallException(LlmCallException.BLOCKED_ADDRESS, "Adresse du fournisseur refusée");
        } catch (UnknownHostException e) {
            throw new LlmCallException(LlmCallException.NETWORK, "Hôte du fournisseur introuvable");
        }
    }

    /**
     * Résolveur pour le client HTTP : mêmes règles que {@link #check}, appliquées à la résolution qui sert
     * réellement à se connecter. Rend exactement les adresses vérifiées, ou lève
     * {@link BlockedAddressException} si l'une d'elles n'est pas publique.
     */
    public DnsResolver asDnsResolver() {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return verifiedAddresses(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    private InetAddress[] verifiedAddresses(String host) throws UnknownHostException {
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new BlockedAddressException();
            }
        }
        return addresses;
    }

    private static String hostOf(String baseUrl) {
        try {
            URI uri = new URI(baseUrl == null ? "" : baseUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new LlmCallException(LlmCallException.NETWORK, "URL du fournisseur invalide");
            }
            return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        } catch (java.net.URISyntaxException e) {
            throw new LlmCallException(LlmCallException.NETWORK, "URL du fournisseur invalide");
        }
    }

    private static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            return isNonPublicV4(b[0] & 0xFF, b[1] & 0xFF);
        }
        // IPv6 : on lit les octets bruts (Java ne reconnaît pas fec0::/10 ni fc00::/7).
        if (embedsIpv4(b)) {
            return isNonPublicV4(b[12] & 0xFF, b[13] & 0xFF);
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return (first & 0xFE) == 0xFC                       // fc00::/7 unique-local
                || (first == 0xFE && (second & 0xC0) == 0x80)  // fe80::/10 lien local
                || (first == 0xFE && (second & 0xC0) == 0xC0)  // fec0::/10 site-local
                || first == 0xFF;                             // ff00::/8 multicast
    }

    private static boolean isNonPublicV4(int a, int b) {
        return a == 0                              // 0.0.0.0/8
                || a == 10                         // 10/8
                || a == 127                        // boucle locale
                || (a == 100 && (b & 0xC0) == 64)  // 100.64/10 CGNAT
                || (a == 169 && b == 254)          // lien local
                || (a == 172 && (b & 0xF0) == 16)  // 172.16/12
                || (a == 192 && b == 168)          // 192.168/16
                || a >= 224;                       // multicast, réservé, diffusion
    }

    /** ::ffff:a.b.c.d (mappée), ::a.b.c.d (compatible) et 64:ff9b::/96 (NAT64) portent une IPv4 dans les 4 derniers octets. */
    private static boolean embedsIpv4(byte[] b) {
        boolean zeroPrefix = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                zeroPrefix = false;
                break;
            }
        }
        if (zeroPrefix && ((b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) || (b[10] == 0 && b[11] == 0))) {
            return true;
        }
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            for (int i = 4; i < 12; i++) {
                if (b[i] != 0) return false;
            }
            return true;
        }
        return false;
    }
}
