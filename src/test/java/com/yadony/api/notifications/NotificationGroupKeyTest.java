package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationGroupKeyTest {

    private final String annId = UUID.randomUUID().toString();
    private final String threadId = UUID.randomUUID().toString();

    @Test
    void offersOnTheSameAnnouncementShareAKey() {
        assertThat(NotificationGroupKey.of("BID_CREATED", Map.of("announcementId", annId, "bidId", UUID.randomUUID().toString())))
                .contains("bid:announcement:" + annId);
        assertThat(NotificationGroupKey.of("bid_negotiation_message", Map.of("announcementId", annId)))
                .contains("bid:announcement:" + annId);
    }

    @Test
    void requestNegotiationsShareTheirThread() {
        assertThat(NotificationGroupKey.of("negotiation_started", Map.of("threadId", threadId)))
                .contains("request:thread:" + threadId);
        assertThat(NotificationGroupKey.of("negotiation_counter", Map.of("threadId", threadId)))
                .contains("request:thread:" + threadId);
        assertThat(NotificationGroupKey.of("negotiation", Map.of("threadId", threadId)))
                .contains("request:thread:" + threadId);
    }

    @Test
    void alertsAndMatchesGroupOnTheirSource() {
        String alertId = UUID.randomUUID().toString();
        assertThat(NotificationGroupKey.of("CORRIDOR_ALERT", Map.of("alertId", alertId, "announcementId", annId)))
                .contains("alert:" + alertId);
        assertThat(NotificationGroupKey.of("CORRIDOR_ALERT", Map.of("corridor", "Paris → Dakar", "announcementId", annId)))
                .contains("alert:corridor:Paris → Dakar");
        assertThat(NotificationGroupKey.of("PACKAGE_MATCH", Map.of("announcementId", annId, "requestId", UUID.randomUUID().toString())))
                .contains("match:announcement:" + annId);
        String travelerId = UUID.randomUUID().toString();
        assertThat(NotificationGroupKey.of("TRAVELER_NEW_ANNOUNCEMENT", Map.of("travelerId", travelerId, "announcementId", annId)))
                .contains("follow:traveler:" + travelerId);
    }

    @Test
    void everythingElseStaysAloneInItsGroup() {
        assertThat(NotificationGroupKey.of("PAYMENT_RELEASED", Map.of("bidId", UUID.randomUUID().toString()))).isEmpty();
        assertThat(NotificationGroupKey.of("DISPUTE_OPENED", Map.of())).isEmpty();
        assertThat(NotificationGroupKey.of("BID_CREATED", Map.of())).isEmpty();
        assertThat(NotificationGroupKey.of(null, null)).isEmpty();
        assertThat(NotificationGroupKey.of("BID_CREATED", null)).isEmpty();
    }

    @Test
    void rejectsIdsThatAreNotUuids() {
        assertThat(NotificationGroupKey.of("BID_CREATED", Map.of("announcementId", "../etc/passwd"))).isEmpty();
    }
}
