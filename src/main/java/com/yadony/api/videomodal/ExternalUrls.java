package com.yadony.api.videomodal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Contrôle des liens externes saisis par l'admin pour une action URL : https uniquement. */
final class ExternalUrls {

    static final int MAX_LENGTH = 2000;

    private ExternalUrls() {
    }

    /**
     * Lien nettoyé (espaces de bord retirés) s'il est une URL https valide de 2000 caractères au
     * plus, avec un hôte. Refuse http, javascript:, data:, mailto: et tout ce qui n'est pas une URL
     * absolue, ainsi que les espaces et caractères de contrôle à l'intérieur.
     */
    static String requireHttps(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty() || url.length() > MAX_LENGTH) return null;
        for (int i = 0; i < url.length(); i++) {
            char ch = url.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))) return null;
            if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            return url;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
