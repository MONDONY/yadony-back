package com.yadony.api.common.media;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Ramène une valeur stockée en base (clé R2 ou URL http(s) héritée, présignée ou non)
 * à la clé R2 qu'elle désigne. Le rapprochement s'en sert pour ne jamais prendre pour
 * orphelin un objet référencé sous une forme ancienne.
 */
public final class MediaKeys {

    /**
     * Préfixes R2 de l'application : SEULE source de vérité, StorageService en tire ses
     * ALLOWED_PREFIXES. Un préfixe absent d'ici ferait passer toutes ses clés pour orphelines.
     */
    public static final List<String> KNOWN_PREFIXES = List.of(
            "package_requests/", "requests/", "tracking/", "users/", "messaging/", "bids/", "reports/", "support/");

    private MediaKeys() {
    }

    /**
     * Clé R2 désignée par {@code raw} : la valeur elle-même si elle commence par un préfixe
     * connu, sinon le chemin à partir du premier segment qui commence par un préfixe connu
     * (URL http(s) : sans hôte, sans query string ni fragment). Vide si rien ne correspond.
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String value = raw.trim();
        String path = isHttp(value) ? pathOf(value) : value;
        return keyFromPath(path);
    }

    /** Valeurs brutes ramenées à leur clé, gardées seulement si la clé commence par {@code prefix}. */
    public static Set<String> keysUnder(String prefix, Collection<String> rawValues) {
        Set<String> keys = new HashSet<>();
        for (String raw : rawValues) {
            normalize(raw).filter(k -> k.startsWith(prefix)).ifPresent(keys::add);
        }
        return keys;
    }

    private static boolean isHttp(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null) return path;
        } catch (IllegalArgumentException ignored) {
            // URL mal formée (espace, caractère interdit) : découpage à la main ci-dessous.
        }
        String rest = url.substring(url.indexOf("://") + 3);
        int cut = indexOfAny(rest, '?', '#');
        if (cut >= 0) rest = rest.substring(0, cut);
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(slash) : "";
    }

    private static Optional<String> keyFromPath(String path) {
        for (int i = 0; i < path.length(); i++) {
            if (i > 0 && path.charAt(i - 1) != '/') continue;
            for (String prefix : KNOWN_PREFIXES) {
                if (path.startsWith(prefix, i)) return Optional.of(path.substring(i));
            }
        }
        return Optional.empty();
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }
}
