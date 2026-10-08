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

    @Test
    void nineteenArgConstructor_hasNoParcelState_andWitherKeepsEveryOtherField() throws Exception {
        var base = new ConversationResponse(id, bidId, "conv_1", null, "Salut", null, false,
                "Paris", "Dakar", "2026-10-20", 3.0, "IN_TRANSIT", false, false,
                "SENDER_TRAVELER", null, true, true, true);
        assertThat(base.parcelStatus()).isNull();
        assertThat(base.returnPending()).isFalse();

        var withState = base.withParcelState("HANDED_OVER", true);
        assertThat(withState.parcelStatus()).isEqualTo("HANDED_OVER");
        assertThat(withState.returnPending()).isTrue();
        assertThat(withState.withParcelState(null, false)).isEqualTo(base);

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(withState);
        assertThat(json).contains("\"parcelStatus\":\"HANDED_OVER\"").contains("\"returnPending\":true");
    }
}
