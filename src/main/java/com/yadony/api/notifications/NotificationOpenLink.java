package com.yadony.api.notifications;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lien du bouton « Ouvrir Yadony » d'un e-mail de notification : {@code https://yadony.com/ouvrir?type=…&bidId=…}.
 *
 * <p>C'est un lien universel (iOS) / App Link (Android) : sur un téléphone où l'app est
 * installée, il s'ouvre dans l'app, sur l'écran de la notification (l'app le résout comme le
 * toucher d'un push, {@code resolveNotificationRoute}). Sinon, yadony.com/ouvrir propose
 * l'app et les stores. Seuls le type et les identifiants UUID de la charge utile sont repris :
 * rien d'autre ne sort dans l'adresse.
 */
public final class NotificationOpenLink {

    static final String BASE_URL = "https://yadony.com/ouvrir";

    /** Identifiants lus par l'app pour choisir l'écran (même liste que le résolveur de routes). */
    static final List<String> ID_KEYS = List.of("bidId", "announcementId", "requestId", "threadId",
            "cancellationId", "packageRequestId", "conversationId", "alertId");

    private static final Pattern TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private NotificationOpenLink() {}

    /** Lien pour ce type et cette charge utile, ou {@code null} (type absent ou invalide). */
    public static String urlFor(String type, Map<String, String> data) {
        if (type == null || !TYPE.matcher(type).matches()) {
            return null;
        }
        StringBuilder url = new StringBuilder(BASE_URL).append("?type=").append(encode(type));
        if (data != null) {
            for (String key : ID_KEYS) {
                String value = data.get(key);
                if (value != null && UUID_FORMAT.matcher(value).matches()) {
                    url.append('&').append(key).append('=').append(encode(value.toLowerCase(java.util.Locale.ROOT)));
                }
            }
        }
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
