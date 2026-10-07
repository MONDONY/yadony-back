package com.yadony.api.messaging.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Champ {@code notificationsMuted} (FLUTTER-CM) et constructeurs de compatibilité. */
class ConversationResponseTest {

    private final UUID id = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @Test
    void seventeenArgConstructor_defaultsToNotMuted_andKeepsCallAvailable() {
        var r = new ConversationResponse(id, bidId, "conv_1", null, null, null, false,
                null, null, null, null, null, false, false, "SENDER_TRAVELER", null, true);
        assertThat(r.callAvailable()).isTrue();
        assertThat(r.notificationsMuted()).isFalse();
    }

    @Test
    void olderConstructors_defaultToNotMuted() {
        var sixteen = new ConversationResponse(id, bidId, "conv_1", null, null, null, false,
                null, null, null, null, null, false, false, "SENDER_TRAVELER", null);
        var fourteen = new ConversationResponse(id, bidId, "conv_1", null, null, null, false,
                null, null, null, null, null, false, false);
        assertThat(sixteen.notificationsMuted()).isFalse();
        assertThat(fourteen.notificationsMuted()).isFalse();
    }

    @Test
    void canonicalConstructor_serializesNotificationsMuted() throws Exception {
        var r = new ConversationResponse(id, bidId, "conv_1", null, null, null, false,
                null, null, null, null, null, false, false, "SENDER_TRAVELER", null, false, true);
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(r);
        assertThat(json).contains("\"notificationsMuted\":true");
    }
}
