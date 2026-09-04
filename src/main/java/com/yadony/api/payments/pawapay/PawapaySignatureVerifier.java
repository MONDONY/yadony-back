package com.yadony.api.payments.pawapay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Vérifie un callback pawaPay signé selon RFC 9421 (HTTP Message Signatures) et RFC 9530
 * ({@code Content-Digest}). Base de signature : une ligne {@code "composant": valeur} par
 * composant couvert, dans l'ordre de {@code Signature-Input}, puis
 * {@code "@signature-params": …}. Signature ECDSA au format P1363 (r||s) comme l'exige la RFC.
 *
 * <p>Le vérifieur applique sa propre politique de sécurité indépendamment de ce que le signataire
 * a choisi de couvrir (RFC 9421 §3.2, MUST côté vérifieur) :
 * <ul>
 *   <li>couverture minimale obligatoire ({@code @method}, {@code @authority}, {@code @path},
 *       {@code content-digest}) — sinon un signataire pourrait ne rien couvrir d'utile ;</li>
 *   <li>liste fermée d'algorithmes ({@code alg}), validée avant toute résolution de clé, pour ne
 *       pas faire travailler {@link PawapaySignatureVerifier.KeyResolver} sur une requête déjà
 *       condamnée ;</li>
 *   <li>{@code created} obligatoire et borné ({@code [now-300s, now+60s]}) pour empêcher le
 *       rejeu indéfini d'un callback authentique capturé ; {@code expires}, quand pawaPay
 *       l'envoie, reste contrôlé mais n'est jamais exigé.</li>
 * </ul>
 */
@Component
public class PawapaySignatureVerifier {

    @FunctionalInterface
    public interface KeyResolver {
        Optional<PublicKey> resolve(String keyId);
    }

    private static final Pattern INPUT = Pattern.compile("^([A-Za-z0-9_-]+)=\\((.*?)\\)(.*)$");
    private static final Pattern PARAM = Pattern.compile(";\\s*([a-z]+)=(\"([^\"]*)\"|([0-9]+))");
    private static final Set<String> REQUIRED_COMPONENTS = Set.of("@method", "@authority", "@path", "content-digest");
    private static final Set<String> SUPPORTED_ALGORITHMS =
            Set.of("ecdsa-p256-sha256", "ecdsa-p384-sha384", "rsa-v1_5-sha256", "rsa-pss-sha512");
    private static final long CLOCK_SKEW_SECONDS = 60;
    private static final long MAX_SIGNATURE_AGE_SECONDS = 300;
    private static final int MAX_REFLECTED_INPUT_LENGTH = 64;

    private final KeyResolver keys;
    private final Clock clock;

    @Autowired
    public PawapaySignatureVerifier(KeyResolver keys) {
        this(keys, Clock.systemUTC());
    }

    public PawapaySignatureVerifier(KeyResolver keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    public void verify(String method, String authority, String path, Map<String, String> headers, byte[] body) {
        String input = required(headers, "signature-input");
        String signature = required(headers, "signature");
        String digest = required(headers, "content-digest");
        verifyDigest(digest, body);

        Matcher m = INPUT.matcher(input.trim());
        if (!m.matches()) throw new PawapaySignatureException("Signature-Input illisible");
        String label = m.group(1);
        List<String> components = new ArrayList<>();
        for (String c : m.group(2).trim().split("\\s+")) {
            if (!c.isBlank()) components.add(c.replace("\"", ""));
        }
        if (!components.containsAll(REQUIRED_COMPONENTS)) {
            throw new PawapaySignatureException("couverture de signature insuffisante");
        }

        String params = m.group(3);
        String alg = param(params, "alg");
        String keyId = param(params, "keyid");
        if (alg == null) throw new PawapaySignatureException("Paramètre alg absent");
        if (keyId == null) throw new PawapaySignatureException("Paramètre keyid absent");
        requireSupportedAlgorithm(alg);
        verifyTemporalWindow(params);

        StringBuilder base = new StringBuilder();
        for (String c : components) {
            String value = switch (c) {
                case "@method" -> method.toUpperCase(Locale.ROOT);
                case "@authority" -> authority.toLowerCase(Locale.ROOT);
                case "@path" -> path;
                default -> Optional.ofNullable(headers.get(c.toLowerCase(Locale.ROOT)))
                        .orElseThrow(() -> new PawapaySignatureException("En-tête couvert absent : " + truncate(c)));
            };
            base.append('"').append(c).append("\": ").append(value.trim()).append('\n');
        }
        base.append("\"@signature-params\": ").append("(").append(m.group(2)).append(")").append(params);

        byte[] sig = extractSignature(signature, label);
        PublicKey key = keys.resolve(keyId)
                .orElseThrow(() -> new PawapaySignatureException("keyid inconnu : " + truncate(keyId)));
        if (!verifySignature(alg, key, base.toString().getBytes(StandardCharsets.UTF_8), sig)) {
            throw new PawapaySignatureException("Signature invalide");
        }
    }

    /**
     * {@code created} est obligatoire et borné à {@code [now-300s, now+60s]} : sans cette
     * fenêtre, un callback {@code COMPLETED} authentique intercepté serait rejouable
     * indéfiniment (sa signature restant valide indépendamment du temps). {@code expires}, quand
     * pawaPay l'envoie, reste contrôlé avec la même tolérance d'horloge, mais n'est jamais exigé.
     */
    private void verifyTemporalWindow(String params) {
        long now = clock.instant().getEpochSecond();
        String createdRaw = param(params, "created");
        if (createdRaw == null) throw new PawapaySignatureException("Paramètre created absent");
        long created = parseEpochSeconds(createdRaw, "created");
        if (created > now + CLOCK_SKEW_SECONDS) {
            throw new PawapaySignatureException("Signature-Input : created dans le futur (dérive d'horloge)");
        }
        if (created < now - MAX_SIGNATURE_AGE_SECONDS) {
            throw new PawapaySignatureException("Signature-Input : created trop ancien");
        }
        String expiresRaw = param(params, "expires");
        if (expiresRaw != null) {
            long expires = parseEpochSeconds(expiresRaw, "expires");
            if (expires + CLOCK_SKEW_SECONDS < now) {
                throw new PawapaySignatureException("Signature expirée");
            }
        }
    }

    /** Convertit un paramètre numérique de {@code Signature-Input} sans jamais laisser fuir une {@link NumberFormatException}. */
    private static long parseEpochSeconds(String raw, String paramName) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new PawapaySignatureException("Paramètre " + paramName + " illisible");
        }
    }

    private static void requireSupportedAlgorithm(String alg) {
        if (!SUPPORTED_ALGORITHMS.contains(alg)) {
            throw new PawapaySignatureException("alg non supporté : " + truncate(alg));
        }
    }

    private static void verifyDigest(String header, byte[] body) {
        int eq = header.indexOf('=');
        if (eq < 0) throw new PawapaySignatureException("Content-Digest illisible");
        String algo = header.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String encoded = header.substring(eq + 1).trim();
        if (isWrappedByteSequence(encoded)) encoded = encoded.substring(1, encoded.length() - 1);
        String jca = switch (algo) {
            case "sha-256" -> "SHA-256";
            case "sha-512" -> "SHA-512";
            default -> throw new PawapaySignatureException("Content-Digest : algorithme non supporté " + truncate(algo));
        };
        try {
            byte[] expected = MessageDigest.getInstance(jca).digest(body);
            byte[] given = Base64.getDecoder().decode(encoded);
            if (!MessageDigest.isEqual(expected, given)) {
                throw new PawapaySignatureException("Content-Digest ne correspond pas au corps");
            }
        } catch (IllegalArgumentException | java.security.NoSuchAlgorithmException e) {
            throw new PawapaySignatureException("Content-Digest illisible");
        }
    }

    private static byte[] extractSignature(String header, String label) {
        String prefix = label + "=";
        String v = header.trim();
        if (!v.startsWith(prefix)) throw new PawapaySignatureException("Signature : label " + truncate(label) + " absent");
        String encoded = v.substring(prefix.length()).trim();
        if (isWrappedByteSequence(encoded)) encoded = encoded.substring(1, encoded.length() - 1);
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new PawapaySignatureException("Signature illisible");
        }
    }

    /** {@code true} seulement pour une vraie séquence d'octets RFC 8941 {@code :…:} (au moins les deux bornes présentes). */
    private static boolean isWrappedByteSequence(String value) {
        return value.length() >= 2 && value.startsWith(":") && value.endsWith(":");
    }

    private static boolean verifySignature(String alg, PublicKey key, byte[] data, byte[] sig) {
        try {
            Signature verifier = switch (alg) {
                case "ecdsa-p256-sha256" -> Signature.getInstance("SHA256withECDSAinP1363Format");
                case "ecdsa-p384-sha384" -> Signature.getInstance("SHA384withECDSAinP1363Format");
                case "rsa-v1_5-sha256" -> Signature.getInstance("SHA256withRSA");
                case "rsa-pss-sha512" -> {
                    Signature s = Signature.getInstance("RSASSA-PSS");
                    s.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1));
                    yield s;
                }
                default -> throw new PawapaySignatureException("alg non supporté : " + truncate(alg));
            };
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(sig);
        } catch (PawapaySignatureException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    private static String param(String params, String name) {
        Matcher p = PARAM.matcher(params);
        while (p.find()) {
            if (p.group(1).equals(name)) return p.group(3) != null ? p.group(3) : p.group(4);
        }
        return null;
    }

    private static String required(Map<String, String> headers, String name) {
        String v = headers.get(name);
        if (v == null || v.isBlank()) throw new PawapaySignatureException("En-tête " + name + " absent");
        return v;
    }

    /** Borne à 64 caractères une valeur non authentifiée avant de la refléter dans un message d'exception. */
    private static String truncate(String value) {
        if (value == null) return "?";
        return value.length() > MAX_REFLECTED_INPUT_LENGTH ? value.substring(0, MAX_REFLECTED_INPUT_LENGTH) : value;
    }
}
