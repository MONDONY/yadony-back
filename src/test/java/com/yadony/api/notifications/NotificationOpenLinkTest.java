package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationOpenLinkTest {

    @Test
    void type_alone_whenThePayloadHasNoId() {
        assertThat(NotificationOpenLink.urlFor("PAYMENT_RELEASED", Map.of()))
                .isEqualTo("https://yadony.com/ouvrir?type=PAYMENT_RELEASED");
        assertThat(NotificationOpenLink.urlFor("PAYMENT_RELEASED", null))
                .isEqualTo("https://yadony.com/ouvrir?type=PAYMENT_RELEASED");
    }

    @Test
    void keepsKnownUuidIds_inAFixedOrder_lowercased() {
        UUID bid = UUID.randomUUID();
        UUID announcement = UUID.randomUUID();
        Map<String, String> data = new HashMap<>();
        data.put("announcementId", announcement.toString().toUpperCase());
        data.put("bidId", bid.toString());

        assertThat(NotificationOpenLink.urlFor("BID_CREATED", data))
                .isEqualTo("https://yadony.com/ouvrir?type=BID_CREATED&bidId=" + bid
                        + "&announcementId=" + announcement);
    }

    @Test
    void dropsNonUuidValues_andUnknownKeys() {
        Map<String, String> data = new HashMap<>();
        data.put("bidId", "../admin");
        data.put("amount", "120");
        data.put("conversationId", UUID.randomUUID() + "&x=1");

        assertThat(NotificationOpenLink.urlFor("BID_CREATED", data))
                .isEqualTo("https://yadony.com/ouvrir?type=BID_CREATED");
    }

    @Test
    void noLink_whenTheTypeIsMissingOrMalformed() {
        assertThat(NotificationOpenLink.urlFor(null, Map.of())).isNull();
        assertThat(NotificationOpenLink.urlFor("bid_created", Map.of())).isNull();
        assertThat(NotificationOpenLink.urlFor("BID&x=1", Map.of())).isNull();
        assertThat(NotificationOpenLink.urlFor("AB", Map.of())).isNull();
    }
}
