package com.yadony.api.assistant.provider;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Adresse d'un fournisseur saisie par un admin et appelée par le serveur : https seulement,
 * jamais une IP ni un nom interne (protection contre l'appel de services internes).
 */
public final class BaseUrlValidator {

    // Hôte fait seulement de chiffres et de points : couvre toutes les notations IPv4 (octal à 4 chiffres, 127.1...)
    private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+");

    private BaseUrlValidator() {
    }

    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("L'adresse est obligatoire.");
        String url = raw.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.isEmpty() || url.length() > 255) throw new IllegalArgumentException("L'adresse doit faire de 1 à 255 caractères.");
        if (url.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("L'adresse ne doit pas contenir d'espace.");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Adresse invalide.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("L'adresse doit commencer par https://.");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("L'adresse doit contenir un nom de domaine.");
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("L'adresse ne doit pas contenir d'identifiants.");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("L'adresse ne doit contenir ni ? ni #.");
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") || h.contains(":") || NUMERIC_HOST.matcher(h).matches()) {
            throw new IllegalArgumentException("Utilisez un nom de domaine, pas une adresse IP.");
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || !h.contains(".")) {
            throw new IllegalArgumentException("Adresse interne refusée.");
        }
        return url;
    }
}
