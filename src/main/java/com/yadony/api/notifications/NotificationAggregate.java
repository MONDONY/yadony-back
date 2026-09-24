package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;

import java.util.Map;
import java.util.Optional;

/**
 * La ligne agrégée du feed : à partir de {@link #MIN_COUNT} notifications non
 * lues de même {@code groupKey}, la liste n'en montre qu'une, qui porte le
 * nombre et renvoie vers la ressource commune du groupe (les offres de
 * l'annonce, le fil de négociation, les correspondances de l'alerte) plutôt
 * que vers la dernière notification.
 *
 * <p>Le titre respecte {@link NotificationCaps#TITLE_MAX} : au-delà de 99 le
 * nombre disparaît, comme dans le récapitulatif d'alerte. Le corps est celui
 * de la notification la plus récente : c'est la seule information qui n'est
 * pas déjà dans le titre.
 */
public final class NotificationAggregate {

    public static final int MIN_COUNT = 3;

    private static final String SCHEME = "yadony://";

    private NotificationAggregate() {}

    public static NotificationText text(Messages m, String groupKey, int count, NotificationEntity latest) {
        Kind kind = Kind.of(groupKey);
        String title = switch (kind) {
            case BIDS -> label(m, count, "notification.aggregate.bids");
            case THREAD -> label(m, count, "notification.aggregate.thread");
            case ALERT -> packagesAlert(latest)
                    ? label(m, count, "notification.aggregate.alert-parcels")
                    : label(m, count, "notification.aggregate.alert-trips");
            case MATCH -> label(m, count, "notification.aggregate.match");
            case FOLLOW -> label(m, count, "notification.aggregate.follow");
            case OTHER -> latest.getTitle();
        };
        return new NotificationText(title, latest.getBody());
    }

    /**
     * Destination du groupe. Absente seulement pour une clé inconnue ou une
     * alerte identifiée par son corridor plutôt que par son id : la ligne
     * garde alors le deeplink de la notification la plus récente.
     */
    public static Optional<String> deeplink(String groupKey, NotificationEntity latest) {
        Kind kind = Kind.of(groupKey);
        String id = groupKey.substring(kind.prefix.length());
        Optional<String> target = switch (kind) {
            case BIDS -> Optional.of("announcements/" + id + "/bids");
            case THREAD -> Optional.of("negotiations/" + id);
            case ALERT -> NotificationGroupKey.UUID_PATTERN.matcher(id).matches()
                    ? Optional.of("corridor-alerts/" + id + "/matches")
                    : Optional.empty();
            case MATCH -> Optional.of("announcements/" + id + "/trip");
            case FOLLOW -> Optional.of("travelers/" + id);
            case OTHER -> Optional.empty();
        };
        return target.map(path -> SCHEME + path)
                .or(() -> Optional.ofNullable(latest.getDeeplink()));
    }

    private static String label(Messages m, int count, String key) {
        return count < 100 ? m.get(key, count) : m.get(key + ".many");
    }

    private static boolean packagesAlert(NotificationEntity latest) {
        Map<String, String> data = latest.getData();
        return data != null && "TRAVELER_WANTS_PACKAGES".equals(data.get("direction"));
    }

    /** Les familles de clés de {@link NotificationGroupKey}. */
    enum Kind {
        BIDS("bid:announcement:"),
        THREAD("request:thread:"),
        ALERT("alert:"),
        MATCH("match:announcement:"),
        FOLLOW("follow:traveler:"),
        OTHER("");

        final String prefix;

        Kind(String prefix) { this.prefix = prefix; }

        static Kind of(String groupKey) {
            if (groupKey == null) return OTHER;
            for (Kind k : values()) {
                if (k != OTHER && groupKey.startsWith(k.prefix)) return k;
            }
            return OTHER;
        }
    }
}
