package com.yadony.api.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ligne agrégée du feed : titre borné, destination du groupe")
class NotificationAggregateTest {

    private static final String ANN = "8d2b6c1e-0f4a-4c7b-9a1d-2e3f4a5b6c7d";
    private static final String THREAD = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
    private static final String ALERT = "0f0e0d0c-0b0a-4908-8706-050403020100";
    private static final String TRAVELER = "9e8d7c6b-5a4f-4e3d-8c2b-1a0f9e8d7c6b";

    private static NotificationEntity latest(String type, Map<String, String> data) {
        return new NotificationEntity(UUID.randomUUID(), type, "Titre de la dernière", "Corps de la dernière.",
                data, false);
    }

    @Test
    void bids_titleCountsRequestsAndLinksToAnnouncementBids() {
        var latest = latest("BID_CREATED", Map.of("type", "BID_CREATED", "announcementId", ANN,
                "bidId", UUID.randomUUID().toString()));

        var text = NotificationAggregate.text("bid:announcement:" + ANN, 3, latest);

        assertThat(text.title()).isEqualTo("3 demandes d'envoi");
        assertThat(text.body()).isEqualTo("Corps de la dernière.");
        assertThat(NotificationAggregate.deeplink("bid:announcement:" + ANN, latest))
                .contains("yadony://announcements/" + ANN + "/bids");
    }

    @Test
    void thread_linksToNegotiation() {
        var latest = latest("negotiation_counter", Map.of("type", "negotiation_counter", "threadId", THREAD));

        assertThat(NotificationAggregate.text("request:thread:" + THREAD, 4, latest).title())
                .isEqualTo("4 tours de négociation");
        assertThat(NotificationAggregate.deeplink("request:thread:" + THREAD, latest))
                .contains("yadony://negotiations/" + THREAD);
    }

    @Test
    void alert_wordsFollowDirectionAndLinkToMatches() {
        var trips = latest("CORRIDOR_ALERT", Map.of("type", "CORRIDOR_ALERT", "alertId", ALERT,
                "direction", "SENDER_WANTS_TRIPS"));
        var packages = latest("CORRIDOR_ALERT", Map.of("type", "CORRIDOR_ALERT", "alertId", ALERT,
                "direction", "TRAVELER_WANTS_PACKAGES"));

        assertThat(NotificationAggregate.text("alert:" + ALERT, 5, trips).title())
                .isEqualTo("5 trajets pour votre alerte");
        assertThat(NotificationAggregate.text("alert:" + ALERT, 5, packages).title())
                .isEqualTo("5 colis pour votre alerte");
        assertThat(NotificationAggregate.deeplink("alert:" + ALERT, trips))
                .contains("yadony://corridor-alerts/" + ALERT + "/matches");
    }

    @Test
    void alertByCorridor_keepsLatestDeeplink() {
        var latest = latest("CORRIDOR_ALERT", Map.of("type", "CORRIDOR_ALERT", "announcementId", ANN,
                "corridor", "Paris → Dakar"));

        assertThat(NotificationAggregate.deeplink("alert:corridor:Paris → Dakar", latest))
                .contains("yadony://traveler/" + ANN);
    }

    @Test
    void match_andFollow_linkToTripAndTravelerProfile() {
        var match = latest("PACKAGE_MATCH", Map.of("type", "PACKAGE_MATCH", "requestId", THREAD, "corridor", "x"));
        var follow = latest("TRAVELER_NEW_ANNOUNCEMENT", Map.of("type", "TRAVELER_NEW_ANNOUNCEMENT",
                "announcementId", ANN, "travelerId", TRAVELER));

        assertThat(NotificationAggregate.text("match:announcement:" + ANN, 3, match).title())
                .isEqualTo("3 colis pour votre trajet");
        assertThat(NotificationAggregate.deeplink("match:announcement:" + ANN, match))
                .contains("yadony://announcements/" + ANN + "/trip");
        assertThat(NotificationAggregate.text("follow:traveler:" + TRAVELER, 3, follow).title())
                .isEqualTo("3 trajets publiés");
        assertThat(NotificationAggregate.deeplink("follow:traveler:" + TRAVELER, follow))
                .contains("yadony://travelers/" + TRAVELER);
    }

    @Test
    void unknownKind_fallsBackToLatestTitleAndDeeplink() {
        var latest = latest("BID_ACCEPTED", Map.of("type", "BID_ACCEPTED", "bidId", THREAD));

        assertThat(NotificationAggregate.text("mystery:" + ANN, 3, latest).title()).isEqualTo("Titre de la dernière");
        assertThat(NotificationAggregate.deeplink("mystery:" + ANN, latest)).contains("yadony://bids/" + THREAD);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 9, 99, 100, 1234})
    void titles_neverExceedTitleCap(int count) {
        var trips = latest("CORRIDOR_ALERT", Map.of("direction", "SENDER_WANTS_TRIPS"));
        for (String key : new String[]{"bid:announcement:" + ANN, "request:thread:" + THREAD, "alert:" + ALERT,
                "match:announcement:" + ANN, "follow:traveler:" + TRAVELER}) {
            String title = NotificationAggregate.text(key, count, trips).title();
            assertThat(title.length()).as(key + " / " + count + " : " + title)
                    .isLessThanOrEqualTo(NotificationCaps.TITLE_MAX);
            if (count >= 100) assertThat(title).doesNotContain(String.valueOf(count));
        }
    }
}
