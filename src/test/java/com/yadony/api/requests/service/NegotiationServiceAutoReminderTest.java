package com.yadony.api.requests.service;

import com.yadony.api.requests.entity.NegotiationThreadEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Le rappel automatique n'est montré dans le fil que tant qu'il reste d'actualité. */
class NegotiationServiceAutoReminderTest {

    private static final LocalDateTime LAST_ACTIVITY = LocalDateTime.of(2026, 10, 25, 10, 0);

    private NegotiationThreadEntity thread(LocalDateTime autoReminderAt, LocalDateTime lastActivityAt) {
        NegotiationThreadEntity t = new NegotiationThreadEntity();
        t.setAutoReminderAt(autoReminderAt);
        t.setLastActivityAt(lastActivityAt);
        return t;
    }

    @Test
    @DisplayName("aucun rappel : null")
    void noReminder() {
        assertThat(NegotiationService.autoReminderSentAt(thread(null, LAST_ACTIVITY))).isNull();
    }

    @Test
    @DisplayName("rappel postérieur à la dernière activité : exposé")
    void reminderAfterLastActivity() {
        LocalDateTime sent = LAST_ACTIVITY.plusHours(24);

        assertThat(NegotiationService.autoReminderSentAt(thread(sent, LAST_ACTIVITY))).isEqualTo(sent);
    }

    @Test
    @DisplayName("nouveau message depuis le rappel : masqué")
    void reminderBeforeLastActivity() {
        LocalDateTime sent = LAST_ACTIVITY.minusHours(30);

        assertThat(NegotiationService.autoReminderSentAt(thread(sent, LAST_ACTIVITY))).isNull();
    }

    @Test
    @DisplayName("dernière activité inconnue : masqué")
    void unknownLastActivity() {
        assertThat(NegotiationService.autoReminderSentAt(thread(LAST_ACTIVITY, null))).isNull();
    }
}
