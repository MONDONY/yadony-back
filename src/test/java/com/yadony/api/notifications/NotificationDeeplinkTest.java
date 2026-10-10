package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeeplinkTest {

    private final String bidId = UUID.randomUUID().toString();
    private final String annId = UUID.randomUUID().toString();
    private final String threadId = UUID.randomUUID().toString();

    /** Sans bidId (notification persistée avant ce correctif), aucun lien : l'app ouvre l'écran générique. */
    @Test
    void tripArrivedWithoutBidId_hasNoDeeplink() {
        assertThat(NotificationDeeplink.of("TRIP_ARRIVED", Map.of("announcementId", bidId))).isEmpty();
    }

    /** Même table que notification_route_resolver.dart : le ticket, sinon la liste des tickets. */
    @Test
    void supportMessageOpensTheTicket() {
        String ticketId = UUID.randomUUID().toString();
        assertThat(NotificationDeeplink.of("SUPPORT_MESSAGE", Map.of("ticketId", ticketId)))
                .contains("yadony://support/tickets/" + ticketId);
        assertThat(NotificationDeeplink.of("SUPPORT_MESSAGE", Map.of("ticketId", "../admin")))
                .contains("yadony://support");
        assertThat(NotificationDeeplink.of("SUPPORT_MESSAGE", Map.of()))
                .contains("yadony://support");
    }

    @Test
    void bidLifecycleOpensTheBid() {
        for (String type : new String[]{"BID_ACCEPTED", "DELIVERY_CONFIRMED", "PAYMENT_RELEASED", "DISPUTE_OPENED",
                "PARCEL_REFUSED", "BID_EXPIRED", "CONFIRMATION_CODE_READY", "CONFIRMATION_CODE_BLOCKED", "DELIVERY_NOSHOW_REPORTED",
                "MM_PAYMENT_PENDING", "HANDOVER_REMINDER_H2", "MOBILE_MONEY_PAYMENT_CONFIRMED",
                "MOBILE_MONEY_PAYMENT_FAILED", "MM_PAYMENT_EXPIRED", "BID_CANCELLED_BEFORE_PAYMENT", "TRIP_ARRIVED", "PARCEL_RETURNED",
                "PARCEL_RETURN_REQUIRED", "PARCEL_RETURN_TO_SENDER", "RETURN_DEADLINE_WARNING", "RETURN_DEADLINE_EXPIRED",
                "automation_last_minute"}) {
            assertThat(NotificationDeeplink.of(type, Map.of("bidId", bidId)))
                    .as(type).contains("yadony://bids/" + bidId);
        }
    }

    /** Demande supprimée à la date limite de dépôt (pas de bidId) : le trajet. */
    @Test
    void bidExpiredWithoutBidOpensTheTrip() {
        String announcementId = UUID.randomUUID().toString();
        assertThat(NotificationDeeplink.of("BID_EXPIRED", Map.of("announcementId", announcementId)))
                .contains("yadony://traveler/" + announcementId);
        assertThat(NotificationDeeplink.of("BID_EXPIRED", Map.of("bidId", bidId, "announcementId", announcementId)))
                .contains("yadony://bids/" + bidId);
        assertThat(NotificationDeeplink.of("BID_EXPIRED", Map.of())).isEmpty();
    }

    /** FLUTTER-G2 : la demande du voyageur ouvre le colis directement sur la régénération du code. */
    @Test
    void codeRequestedOpensTheBidOnTheCodeRenewal() {
        assertThat(NotificationDeeplink.of("CONFIRMATION_CODE_REQUESTED", Map.of("bidId", bidId)))
                .contains("yadony://bids/" + bidId + "?action=new-code");
        assertThat(NotificationDeeplink.of("CONFIRMATION_CODE_REQUESTED", Map.of("bidId", "../admin"))).isEmpty();
        assertThat(NotificationCategory.fromType("CONFIRMATION_CODE_REQUESTED")).isEqualTo(NotificationCategory.COLIS);
    }

    /** Le destinataire suit son colis depuis ses réceptions ; l'expéditeur reste sur le bid. */
    @Test
    void recipientTypesOpenTheReception() {
        for (String type : new String[]{"RECIPIENT_PARCEL_INCOMING", "RECIPIENT_PARCEL_ANNOUNCED",
                "RECIPIENT_PARCEL_DEPARTED",
                "RECIPIENT_PARCEL_ARRIVED", "RECIPIENT_PARCEL_DELIVERED", "RECIPIENT_PARCEL_CANCELLED",
                "RECIPIENT_PARCEL_RESCHEDULED", "RECIPIENT_PICKUP_UPDATED"}) {
            assertThat(NotificationCategory.fromType(type)).as(type).isEqualTo(NotificationCategory.COLIS);
            assertThat(NotificationDeeplink.of(type, Map.of("bidId", bidId)))
                    .as(type).contains("yadony://receptions/" + bidId);
        }
        assertThat(NotificationDeeplink.of("RECIPIENT_CONFIRMED", Map.of("bidId", bidId)))
                .contains("yadony://bids/" + bidId);
        assertThat(NotificationDeeplink.of("RECIPIENT_DECLINED", Map.of("bidId", bidId)))
                .contains("yadony://bids/" + bidId);
        assertThat(NotificationDeeplink.of("RECIPIENT_WITHDRAWN", Map.of("bidId", bidId)))
                .contains("yadony://bids/" + bidId);
        assertThat(NotificationDeeplink.of("RECIPIENT_REPLACEMENT_REQUESTED", Map.of("bidId", bidId)))
                .contains("yadony://bids/" + bidId);
        assertThat(NotificationCategory.fromType("RECIPIENT_REPLACEMENT_REQUESTED")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("RECIPIENT_PARCEL_INCOMING")).isEqualTo(NotificationCategory.COLIS);
    }

    /** Changement de destinataire : l'ancien retombe sur son Suivi, le voyageur ouvre le colis. */
    @Test
    void recipientChangeTypes() {
        assertThat(NotificationDeeplink.of("RECIPIENT_PARCEL_REASSIGNED", Map.of("bidId", bidId)))
                .contains("yadony://tracking");
        assertThat(NotificationDeeplink.of("RECIPIENT_PARCEL_REASSIGNED", Map.of()))
                .contains("yadony://tracking");
        assertThat(NotificationDeeplink.of("RECIPIENT_CHANGED", Map.of("bidId", bidId)))
                .contains("yadony://bids/" + bidId);
        assertThat(NotificationCategory.fromType("RECIPIENT_PARCEL_REASSIGNED")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("RECIPIENT_CHANGED")).isEqualTo(NotificationCategory.COLIS);
    }

    /** Invitations au carnet (lot 4) : l'invité ouvre ses demandes, l'inviteur son carnet. */
    @Test
    void recipientInvitationTypes() {
        assertThat(NotificationDeeplink.of("RECIPIENT_INVITATION", Map.of()))
                .contains("yadony://recipient-invitations");
        assertThat(NotificationDeeplink.of("RECIPIENT_INVITATION_ACCEPTED", Map.of()))
                .contains("yadony://profile/recipients");
        assertThat(NotificationCategory.fromType("RECIPIENT_INVITATION")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationCategory.fromType("RECIPIENT_INVITATION_ACCEPTED")).isEqualTo(NotificationCategory.COLIS);
        assertThat(NotificationDeeplink.of("RECIPIENT_INVITATION_REMOVED", Map.of()))
                .contains("yadony://recipient-invitations");
        assertThat(NotificationCategory.fromType("RECIPIENT_INVITATION_REMOVED")).isEqualTo(NotificationCategory.COLIS);
    }

    @Test
    void newOfferOpensTheRequestInReceivedRequests() {
        assertThat(NotificationDeeplink.of("BID_CREATED", Map.of("announcementId", annId, "bidId", bidId)))
                .contains("yadony://demandes?bid=" + bidId);
    }

    @Test
    void newOfferWithoutBidFallsBackToTheOffersOfTheAnnouncement() {
        assertThat(NotificationDeeplink.of("BID_CREATED", Map.of("announcementId", annId)))
                .contains("yadony://announcements/" + annId + "/bids");
        assertThat(NotificationDeeplink.of("BID_CREATED", Map.of("announcementId", annId, "bidId", "../admin")))
                .contains("yadony://announcements/" + annId + "/bids");
    }

    @Test
    void rejectionsAndCancellationsPreferTheRematch() {
        String cancellationId = UUID.randomUUID().toString();
        assertThat(NotificationDeeplink.of("BID_REJECTED", Map.of("bidId", bidId, "cancellationId", cancellationId)))
                .contains("yadony://cancellations/" + cancellationId + "/rematch");
        assertThat(NotificationDeeplink.of("BID_REJECTED", Map.of("bidId", bidId)))
                .contains("yadony://bids/" + bidId);
        assertThat(NotificationDeeplink.of("TRIP_CANCELLED", Map.of("cancellationId", cancellationId)))
                .contains("yadony://cancellations/" + cancellationId + "/rematch");
        assertThat(NotificationDeeplink.of("TRIP_CANCELLED", Map.of("bidId", bidId)))
                .contains("yadony://bids/" + bidId);
        assertThat(NotificationDeeplink.of("TRIP_CANCELLED", Map.of()))
                .contains("yadony://profile/shipments/history");
    }

    @Test
    void negotiationsOpenTheirThread() {
        assertThat(NotificationDeeplink.of("negotiation_started", Map.of("threadId", threadId)))
                .contains("yadony://negotiations/" + threadId);
        assertThat(NotificationDeeplink.of("negotiation_commission_declined", Map.of("threadId", threadId)))
                .contains("yadony://negotiations/" + threadId);
        assertThat(NotificationDeeplink.of("request_accepted", Map.of("threadId", threadId)))
                .contains("yadony://negotiations/" + threadId);
        String requestId = UUID.randomUUID().toString();
        assertThat(NotificationDeeplink.of("request_expired", Map.of("packageRequestId", requestId)))
                .contains("yadony://package-requests/" + requestId);
    }

    @Test
    void tripsAlertsAndMatches() {
        String requestId = UUID.randomUUID().toString();
        assertThat(NotificationDeeplink.of("TRAVELER_INVITE", Map.of("requestId", requestId)))
                .contains("yadony://package-requests/" + requestId + "/public");
        assertThat(NotificationDeeplink.of("PACKAGE_MATCH", Map.of("requestId", requestId)))
                .contains("yadony://package-requests/" + requestId + "/public");
        assertThat(NotificationDeeplink.of("SENDER_INVITE", Map.of("requestId", requestId)))
                .contains("yadony://package-requests/" + requestId + "/public");
        assertThat(NotificationDeeplink.of("TRAVELER_NEW_ANNOUNCEMENT", Map.of("announcementId", annId)))
                .contains("yadony://traveler/" + annId);
        assertThat(NotificationDeeplink.of("CORRIDOR_ALERT", Map.of("announcementId", annId)))
                .contains("yadony://traveler/" + annId);
        assertThat(NotificationDeeplink.of("automation_loyal_sender", Map.of("announcementId", annId)))
                .contains("yadony://traveler/" + annId);
        assertThat(NotificationDeeplink.of("TRIP_IN_PROGRESS", Map.of("announcementId", annId)))
                .contains("yadony://announcements/" + annId + "/trip");
        assertThat(NotificationDeeplink.of("automation_capacity_free", Map.of("announcementId", annId)))
                .contains("yadony://announcements/" + annId + "/trip");
    }

    @Test
    void fixedDestinations() {
        assertThat(NotificationDeeplink.of("KYC_VERIFIED", Map.of())).contains("yadony://kyc/status");
        assertThat(NotificationDeeplink.of("KYC_ACTION_REQUIRED", Map.of())).contains("yadony://kyc/verify");
        assertThat(NotificationDeeplink.of("DISPUTE_UPDATED", Map.of())).contains("yadony://disputes");
        assertThat(NotificationDeeplink.of("DISPUTE_RESOLVED", Map.of())).contains("yadony://disputes");
        assertThat(NotificationDeeplink.of("ACCOUNT_SUSPENDED", Map.of())).contains("yadony://account/disabled");
        assertThat(NotificationDeeplink.of("STRIPE_ONBOARDING_INCOMPLETE", Map.of())).contains("yadony://connect/onboarding/intro");
        assertThat(NotificationDeeplink.of("CARD_EXPIRING", Map.of())).contains("yadony://payments/commission-method");
    }

    @Test
    void messagesOpenTheConversation() {
        String conversationId = UUID.randomUUID().toString();
        assertThat(NotificationDeeplink.of("NEW_MESSAGE", Map.of("conversationId", conversationId)))
                .contains("yadony://conversations/" + conversationId);
        assertThat(NotificationDeeplink.of("NEW_MESSAGE", Map.of())).contains("yadony://messages");
    }

    @Test
    void announcementsAndUnknownTypesHaveNoDestination() {
        // Pas de deeplink : la ligne ouvre l'écran de détail générique.
        assertThat(NotificationDeeplink.of("ADMIN_BROADCAST", Map.of("broadcastId", UUID.randomUUID().toString()))).isEmpty();
        assertThat(NotificationDeeplink.of("PROMO", Map.of())).isEmpty();
        assertThat(NotificationDeeplink.of(null, Map.of())).isEmpty();
        assertThat(NotificationDeeplink.of("BID_ACCEPTED", null)).isEmpty();
    }

    @Test
    void refusesForgedIds() {
        assertThat(NotificationDeeplink.of("BID_ACCEPTED", Map.of("bidId", "../admin"))).isEmpty();
        assertThat(NotificationDeeplink.of("BID_CREATED", Map.of("announcementId", "42"))).isEmpty();
    }

    @Test
    void firstActionReminderOpensFirstSteps() {
        assertThat(NotificationDeeplink.of("FIRST_ACTION_REMINDER", Map.of("type", "FIRST_ACTION_REMINDER")))
                .contains("yadony://first-steps");
    }

    /** FLUTTER-H7 : le fil du trajet, où se trouve le bouton « Régler la commission ». */
    @Test
    void negotiatedCommissionDueOpensTheBidThread() {
        assertThat(NotificationDeeplink.of("BID_NEGOTIATION_COMMISSION_DUE", Map.of("bidId", bidId, "announcementId", annId)))
                .contains("yadony://bids/" + bidId + "/negotiation");
        assertThat(NotificationDeeplink.of("BID_NEGOTIATION_COMMISSION_DUE", Map.of("bidId", "../admin"))).isEmpty();
        // Une demande cash classique garde son lien.
        assertThat(NotificationDeeplink.of("BID_CREATED", Map.of("bidId", bidId)))
                .contains("yadony://demandes?bid=" + bidId);
    }
}
