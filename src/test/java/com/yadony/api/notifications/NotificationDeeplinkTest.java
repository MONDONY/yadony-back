package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeeplinkTest {

    private final String bidId = UUID.randomUUID().toString();
    private final String annId = UUID.randomUUID().toString();
    private final String threadId = UUID.randomUUID().toString();

    @Test
    void bidLifecycleOpensTheBid() {
        for (String type : new String[]{"BID_ACCEPTED", "DELIVERY_CONFIRMED", "PAYMENT_RELEASED", "DISPUTE_OPENED",
                "PARCEL_REFUSED", "BID_EXPIRED", "CONFIRMATION_CODE_READY", "DELIVERY_NOSHOW_REPORTED",
                "MM_PAYMENT_PENDING", "HANDOVER_REMINDER_H2", "MOBILE_MONEY_PAYMENT_CONFIRMED", "PARCEL_RETURNED",
                "RETURN_DEADLINE_WARNING", "RETURN_DEADLINE_EXPIRED", "automation_last_minute"}) {
            assertThat(NotificationDeeplink.of(type, Map.of("bidId", bidId)))
                    .as(type).contains("yadony://bids/" + bidId);
        }
    }

    @Test
    void newOfferOpensTheOffersOfTheAnnouncement() {
        assertThat(NotificationDeeplink.of("BID_CREATED", Map.of("announcementId", annId, "bidId", bidId)))
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
}
