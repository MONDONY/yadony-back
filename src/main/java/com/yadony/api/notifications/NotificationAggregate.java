package com.yadony.api.notifications;

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

    public static NotificationText text(String groupKey, int count, NotificationEntity latest) {
        Kind kind = Kind.of(groupKey);
        String title = switch (kind) {
            case BIDS -> label(count, "demandes d'envoi", "Demandes d'envoi");
            case THREAD -> label(count, "tours de négociation", "Tours de négociation");
            case ALERT -> packagesAlert(latest)
                    ? label(count, "colis pour votre alerte", "Colis pour votre alerte")
                    : label(count, "trajets pour votre alerte", "Trajets pour votre alerte");
            case MATCH -> label(count, "colis pour votre trajet", "Colis pour votre trajet");
            case FOLLOW -> label(count, "trajets publiés", "Trajets publiés");
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

    private static String label(int count, String plural, String capitalized) {
        return count < 100 ? count + " " + plural : capitalized;
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
