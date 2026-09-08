package com.yadony.api.notifications;

import java.util.Map;
import java.util.Optional;

import static com.yadony.api.notifications.NotificationGroupKey.uuid;

/**
 * Destination d'une notification, sous forme de lien {@code yadony://}.
 *
 * <p>C'est le deeplink qui décide du routage côté app : présent, la ligne y va
 * directement, même si son texte est long, parce que ce texte y vit déjà (le
 * message dans la conversation, l'offre sur l'annonce, le litige sur son
 * écran). Absent, et seulement absent, la ligne ouvre l'écran de détail
 * générique, réservé aux annonces plateforme.
 *
 * <p>La table reproduit {@code notification_route_resolver.dart} de l'app, qui
 * reste la référence tant que l'app ne consomme pas ce champ : l'app forme la
 * route en {@code /host/path} à partir du lien, donc
 * {@code yadony://bids/{id}} vaut {@code /bids/{id}}. Les identifiants sont
 * validés comme UUID avant d'entrer dans un lien, comme côté app.
 */
public final class NotificationDeeplink {

    private static final String SCHEME = "yadony://";

    private NotificationDeeplink() {}

    public static Optional<String> of(String type, Map<String, String> data) {
        if (type == null || data == null) return Optional.empty();
        return route(type, data).map(path -> SCHEME + path);
    }

    private static Optional<String> route(String type, Map<String, String> data) {
        Optional<String> bidId = uuid(data, "bidId");
        Optional<String> announcementId = uuid(data, "announcementId");
        Optional<String> requestId = uuid(data, "requestId");
        Optional<String> threadId = uuid(data, "threadId");
        Optional<String> cancellationId = uuid(data, "cancellationId");
        Optional<String> packageRequestId = uuid(data, "packageRequestId");
        Optional<String> conversationId = uuid(data, "conversationId");

        if (type.startsWith("negotiation")) {
            return threadId.map(id -> "negotiations/" + id);
        }
        return switch (type) {
            case "BID_CREATED" -> announcementId.map(id -> "announcements/" + id + "/bids");

            case "BID_ACCEPTED", "DELIVERY_CONFIRMED", "PAYMENT_RELEASED", "DISPUTE_OPENED", "PARCEL_REFUSED",
                 "BID_EXPIRED", "CONFIRMATION_CODE_READY", "DELIVERY_NOSHOW_REPORTED", "MM_PAYMENT_PENDING",
                 "HANDOVER_REMINDER_H2", "MOBILE_MONEY_PAYMENT_CONFIRMED", "MOBILE_MONEY_PAYMENT_FAILED",
                 "MM_PAYMENT_EXPIRED",
                 "PARCEL_RETURNED", "RETURN_DEADLINE_WARNING", "RETURN_DEADLINE_EXPIRED", "automation_last_minute" ->
                    bidId.map(id -> "bids/" + id);

            case "KYC_VERIFIED" -> Optional.of("kyc/status");
            case "KYC_ACTION_REQUIRED" -> Optional.of("kyc/verify");
            case "DISPUTE_UPDATED", "DISPUTE_RESOLVED" -> Optional.of("disputes");

            case "BID_REJECTED" -> cancellationId.map(id -> "cancellations/" + id + "/rematch")
                    .or(() -> bidId.map(id -> "bids/" + id));
            case "TRIP_CANCELLED" -> cancellationId.map(id -> "cancellations/" + id + "/rematch")
                    .or(() -> bidId.map(id -> "bids/" + id))
                    .or(() -> Optional.of("profile/shipments/history"));

            case "request_accepted" -> threadId.map(id -> "negotiations/" + id);
            case "request_expired" -> packageRequestId.map(id -> "package-requests/" + id);
            case "TRAVELER_INVITE", "PACKAGE_MATCH" -> requestId.map(id -> "package-requests/" + id + "/public");
            case "TRAVELER_NEW_ANNOUNCEMENT", "CORRIDOR_ALERT", "automation_loyal_sender" ->
                    announcementId.map(id -> "traveler/" + id);
            case "TRIP_IN_PROGRESS", "automation_capacity_free" ->
                    announcementId.map(id -> "announcements/" + id + "/trip");

            case "NEW_MESSAGE" -> conversationId.map(id -> "conversations/" + id)
                    .or(() -> Optional.of("messages"));

            case "ACCOUNT_SUSPENDED" -> Optional.of("account/disabled");
            case "STRIPE_ONBOARDING_INCOMPLETE" -> Optional.of("connect/onboarding/intro");
            case "CARD_EXPIRING" -> Optional.of("payments/commission-method");

            default -> Optional.empty();
        };
    }
}
