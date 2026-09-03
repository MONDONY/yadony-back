package com.yadony.api.notifications;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Clé d'agrégation d'une notification : deux notifications de même clé sont
 * présentées ensemble par l'app à partir de trois (« X, Y et N autres »).
 *
 * <p>Seul ce qui se répète légitimement partage une clé : plusieurs offres sur
 * la même annonce, les tours d'une même négociation, les trajets d'une même
 * alerte. Tout le reste reste seul dans son groupe ; l'entité y répond alors
 * par {@code notif:{id}} (voir {@link NotificationEntity#getGroupKey()}).
 *
 * <p>Les identifiants sont validés comme UUID avant d'entrer dans une clé,
 * comme le fait l'app pour ses routes : une clé est servie telle quelle.
 */
public final class NotificationGroupKey {

    static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private NotificationGroupKey() {}

    public static Optional<String> of(String type, Map<String, String> data) {
        if (type == null || data == null) return Optional.empty();
        return switch (type) {
            case "BID_CREATED", "bid_negotiation_message" ->
                    uuid(data, "announcementId").map(id -> "bid:announcement:" + id);
            case "negotiation", "negotiation_started", "negotiation_counter" ->
                    uuid(data, "threadId").map(id -> "request:thread:" + id);
            case "CORRIDOR_ALERT" ->
                    uuid(data, "alertId").map(id -> "alert:" + id)
                            .or(() -> text(data, "corridor").map(c -> "alert:corridor:" + c));
            case "PACKAGE_MATCH" ->
                    uuid(data, "announcementId").map(id -> "match:announcement:" + id);
            case "TRAVELER_NEW_ANNOUNCEMENT" ->
                    uuid(data, "travelerId").map(id -> "follow:traveler:" + id);
            default -> Optional.empty();
        };
    }

    static Optional<String> uuid(Map<String, String> data, String key) {
        String v = data.get(key);
        return v != null && UUID_PATTERN.matcher(v).matches() ? Optional.of(v) : Optional.empty();
    }

    private static Optional<String> text(Map<String, String> data, String key) {
        String v = data.get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
    }
}
