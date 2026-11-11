package com.yadony.api.videomodal;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Extraction de l'identifiant d'une vidéo YouTube depuis le lien collé dans l'admin.
 * Formes reconnues : youtu.be/ID, youtube.com/watch?v=ID, youtube.com/shorts/ID,
 * youtube.com/embed/ID (avec ou sans www., m., music.). Seul https est accepté (un lien http://
 * est refusé ; un lien sans schéma est lu comme https). ID = 11 caractères [A-Za-z0-9_-].
 */
final class YoutubeUrls {

    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private YoutubeUrls() {
    }

    static Optional<String> videoId(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        URI uri;
        try {
            String trimmed = url.trim();
            uri = URI.create(trimmed.contains("://") ? trimmed : "https://" + trimmed);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https")) return Optional.empty();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();

        String candidate = null;
        if (host.equals("youtu.be")) {
            candidate = firstSegment(path);
        } else if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
            if (path.equals("/watch")) {
                candidate = queryParam(uri.getRawQuery(), "v");
            } else if (path.startsWith("/shorts/") || path.startsWith("/embed/")) {
                candidate = firstSegment(path.substring(path.indexOf('/', 1)));
            }
        }
        return candidate != null && VIDEO_ID.matcher(candidate).matches()
                ? Optional.of(candidate) : Optional.empty();
    }

    private static String firstSegment(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        return slash >= 0 ? p.substring(0, slash) : p;
    }

    private static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }
}
