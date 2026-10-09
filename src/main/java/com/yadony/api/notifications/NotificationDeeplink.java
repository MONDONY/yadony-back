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
        Optional<String> ticketId = uuid(data, "ticketId");

        if (type.startsWith("negotiation")) {
            return threadId.map(id -> "negotiations/" + id);
        }
        return switch (type) {
            // La demande elle-même, ouverte par l'app par-dessus « Demandes
            // reçues » sur « À traiter ». La page de l'annonce montrait d'abord
            // les demandes acceptées et cachait la nouvelle derrière un bouton.
            // Une app antérieure qui ignore la query ouvre « Demandes reçues ».
            case "BID_CREATED" -> bidId.map(id -> "demandes?bid=" + id)
                    .or(() -> announcementId.map(id -> "announcements/" + id + "/bids"));

            // Accord en espèces conclu dans le fil d'un trajet (FLUTTER-H7) : le fil porte
            // le bouton « Régler la commission ».
            case "BID_NEGOTIATION_COMMISSION_DUE" -> bidId.map(id -> "bids/" + id + "/negotiation");

            // Demande du voyageur (FLUTTER-G2) : le colis s'ouvre directement sur la
            // régénération du code. Une app antérieure qui ignore la query ouvre le colis.
            case "CONFIRMATION_CODE_REQUESTED" -> bidId.map(id -> "bids/" + id + "?action=new-code");

            case "BID_ACCEPTED", "DELIVERY_CONFIRMED", "PAYMENT_RELEASED", "DISPUTE_OPENED", "PARCEL_REFUSED",
                 "CONFIRMATION_CODE_READY", "CONFIRMATION_CODE_BLOCKED", "DELIVERY_NOSHOW_REPORTED", "MM_PAYMENT_PENDING",
                 "HANDOVER_REMINDER_H2", "MOBILE_MONEY_PAYMENT_CONFIRMED", "SENDER_NOSHOW_REPORTED", "NOSHOW_DECISION", "MOBILE_MONEY_PAYMENT_FAILED",
                 "MM_PAYMENT_EXPIRED", "TRIP_ARRIVED",
                 "PARCEL_RETURNED", "PARCEL_RETURN_REQUIRED", "PARCEL_RETURN_TO_SENDER", "RETURN_DEADLINE_WARNING", "RETURN_DEADLINE_EXPIRED", "automation_last_minute",
                 "TRIP_RESCHEDULED", "TRIP_RESCHEDULE_KEPT", "TRIP_RESCHEDULE_WITHDRAWN",
                 "RECIPIENT_CONFIRMED", "RECIPIENT_DECLINED", "RECIPIENT_CHANGED",
                 "RECIPIENT_WITHDRAWN", "RECIPIENT_REPLACEMENT_REQUESTED",
                 "DELIVERY_RETRY_APPOINTMENT", "PARCEL_UNCLAIMED" ->
                    bidId.map(id -> "bids/" + id);
            // Sans bidId : demande carte jamais payée, supprimée à la date limite de dépôt.
            // Le trajet plutôt qu'une demande introuvable.
            case "BID_EXPIRED" -> bidId.map(id -> "bids/" + id)
                    .or(() -> announcementId.map(id -> "traveler/" + id));

            // Le destinataire n'est pas partie au colis : il le suit depuis ses réceptions.
            case "RECIPIENT_PARCEL_INCOMING", "RECIPIENT_PARCEL_ANNOUNCED", "RECIPIENT_PARCEL_DEPARTED", "RECIPIENT_PARCEL_ARRIVED",
                 "RECIPIENT_PARCEL_DELIVERED", "RECIPIENT_PARCEL_CANCELLED",
                 "RECIPIENT_PARCEL_RESCHEDULED", "RECIPIENT_PICKUP_UPDATED" -> bidId.map(id -> "receptions/" + id);
            // L'ancien destinataire n'a plus accès au colis : il retombe sur son onglet Suivi.
            case "RECIPIENT_PARCEL_REASSIGNED" -> Optional.of("tracking");
            // Invitations au carnet (lot 4) : l'invité répond depuis ses demandes, l'inviteur
            // retrouve le destinataire ajouté dans son carnet.
            // Retiré du carnet : il retrouve ses expéditeurs autorisés, à jour.
            case "RECIPIENT_INVITATION", "RECIPIENT_INVITATION_REMOVED" -> Optional.of("recipient-invitations");
            case "RECIPIENT_INVITATION_ACCEPTED" -> Optional.of("profile/recipients");

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
            case "TRAVELER_INVITE", "PACKAGE_MATCH", "SENDER_INVITE" ->
                    requestId.map(id -> "package-requests/" + id + "/public");
            case "TRAVELER_NEW_ANNOUNCEMENT", "CORRIDOR_ALERT", "automation_loyal_sender" ->
                    announcementId.map(id -> "traveler/" + id);
            case "TRIP_IN_PROGRESS", "automation_capacity_free" ->
                    announcementId.map(id -> "announcements/" + id + "/trip");

            case "NEW_MESSAGE" -> conversationId.map(id -> "conversations/" + id)
                    .or(() -> Optional.of("messages"));

            case "SUPPORT_MESSAGE" -> ticketId.map(id -> "support/tickets/" + id)
                    .or(() -> Optional.of("support"));

            case "ACCOUNT_SUSPENDED" -> Optional.of("account/disabled");
            case "STRIPE_ONBOARDING_INCOMPLETE" -> Optional.of("connect/onboarding/intro");
            case "FIRST_ACTION_REMINDER" -> Optional.of("first-steps");
            case "CARD_EXPIRING" -> Optional.of("payments/commission-method");

            default -> Optional.empty();
        };
    }
}
