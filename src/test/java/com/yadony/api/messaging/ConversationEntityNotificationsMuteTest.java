package com.yadony.api.messaging;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sourdine par participant (FLUTTER-CM), distincte du mute de modération. */
class ConversationEntityNotificationsMuteTest {

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final ConversationEntity conv =
            new ConversationEntity(UUID.randomUUID(), senderId, travelerId, "conv_x");

    @Test
    void notMutedByDefault() {
        assertThat(conv.isNotificationsMutedBy(senderId)).isFalse();
        assertThat(conv.isNotificationsMutedBy(travelerId)).isFalse();
    }

    @Test
    void senderMute_doesNotAffectTraveler_andViceVersa() {
        conv.muteNotificationsForUser(senderId);
        assertThat(conv.isNotificationsMutedBy(senderId)).isTrue();
        assertThat(conv.isNotificationsMutedBy(travelerId)).isFalse();
        assertThat(conv.getSenderNotificationsMutedAt()).isNotNull();
        assertThat(conv.getTravelerNotificationsMutedAt()).isNull();

        conv.muteNotificationsForUser(travelerId);
        conv.unmuteNotificationsForUser(senderId);
        assertThat(conv.isNotificationsMutedBy(senderId)).isFalse();
        assertThat(conv.isNotificationsMutedBy(travelerId)).isTrue();

        conv.unmuteNotificationsForUser(travelerId);
        assertThat(conv.getTravelerNotificationsMutedAt()).isNull();
    }

    @Test
    void muteTwice_keepsTheOriginalTimestamp() {
        conv.muteNotificationsForUser(travelerId);
        LocalDateTime first = conv.getTravelerNotificationsMutedAt();
        conv.muteNotificationsForUser(travelerId);
        assertThat(conv.getTravelerNotificationsMutedAt()).isEqualTo(first);

        conv.muteNotificationsForUser(senderId);
        LocalDateTime senderFirst = conv.getSenderNotificationsMutedAt();
        conv.muteNotificationsForUser(senderId);
        assertThat(conv.getSenderNotificationsMutedAt()).isEqualTo(senderFirst);
    }

    @Test
    void thirdParty_andNull_areIgnored() {
        UUID stranger = UUID.randomUUID();
        conv.muteNotificationsForUser(stranger);
        conv.unmuteNotificationsForUser(stranger);
        assertThat(conv.isNotificationsMutedBy(stranger)).isFalse();
        assertThat(conv.isNotificationsMutedBy(null)).isFalse();
        assertThat(conv.getSenderNotificationsMutedAt()).isNull();
        assertThat(conv.getTravelerNotificationsMutedAt()).isNull();
    }

    @Test
    void recipientConversation_participantA_canMute() {
        UUID recipientId = UUID.randomUUID();
        ConversationEntity rconv = ConversationEntity.forRecipient(UUID.randomUUID(), recipientId, travelerId, "rconv_x");
        rconv.muteNotificationsForUser(recipientId);
        assertThat(rconv.isNotificationsMutedBy(recipientId)).isTrue();
        assertThat(rconv.isNotificationsMutedBy(travelerId)).isFalse();
    }

    @Test
    void muteDoesNotChangeArchiveOrReadOnly() {
        conv.muteNotificationsForUser(senderId);
        assertThat(conv.isArchivedByUser(senderId)).isFalse();
        assertThat(conv.isReadOnlyFor(senderId)).isFalse();
    }
}
