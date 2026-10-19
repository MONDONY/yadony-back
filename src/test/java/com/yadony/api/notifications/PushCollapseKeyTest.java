package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PushCollapseKeyTest {

    private static final String ID = UUID.randomUUID().toString();

    @Test
    void conversation_ticketAndRepeatedStreams_getAKey() {
        assertThat(PushCollapseKey.of("NEW_MESSAGE", Map.of("conversationId", "conv_" + ID)))
                .contains("chat:conv_" + ID);
        assertThat(PushCollapseKey.of("NEW_MESSAGE", Map.of("conversationId", "rconv_" + ID + "_2")))
                .contains("chat:rconv_" + ID + "_2");
        assertThat(PushCollapseKey.of("SUPPORT_MESSAGE", Map.of("ticketId", ID))).contains("support:" + ID);
        assertThat(PushCollapseKey.of("BID_CREATED", Map.of("announcementId", ID)))
                .contains("bid:announcement:" + ID);
        assertThat(PushCollapseKey.of("negotiation_counter", Map.of("threadId", ID)))
                .contains("request:thread:" + ID);
    }

    /** Les étapes d'un colis ne se remplacent pas : une information non lue disparaîtrait. */
    @Test
    void parcelSteps_andUnknownTypes_haveNoKey() {
        assertThat(PushCollapseKey.of("CONFIRMATION_CODE_READY", Map.of("bidId", ID))).isEmpty();
        assertThat(PushCollapseKey.of("DELIVERY_CONFIRMED", Map.of("bidId", ID))).isEmpty();
        assertThat(PushCollapseKey.of(null, Map.of())).isEmpty();
        assertThat(PushCollapseKey.of("NEW_MESSAGE", null)).isEmpty();
    }

    @Test
    void unsafeOrTooLongIds_areRejected() {
        assertThat(PushCollapseKey.of("NEW_MESSAGE", Map.of("conversationId", "a/b"))).isEmpty();
        assertThat(PushCollapseKey.of("NEW_MESSAGE", Map.of("conversationId", "x".repeat(60)))).isEmpty();
        assertThat(PushCollapseKey.of("SUPPORT_MESSAGE", Map.of("ticketId", "pas-un-uuid"))).isEmpty();
    }
}
