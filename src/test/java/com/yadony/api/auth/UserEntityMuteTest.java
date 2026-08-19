package com.yadony.api.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Lot B : état de coupure de la messagerie, borné dans le temps. */
class UserEntityMuteTest {

    @Test
    void notMutedByDefault() {
        assertThat(new UserEntity().isMessagingMuted(Instant.now())).isFalse();
    }

    @Test
    void mutedWhenDeadlineInFuture() {
        UserEntity u = new UserEntity();
        u.setMessagingMutedUntil(Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(u.isMessagingMuted(Instant.now())).isTrue();
    }

    @Test
    void notMutedWhenDeadlinePassed() {
        UserEntity u = new UserEntity();
        u.setMessagingMutedUntil(Instant.now().minus(1, ChronoUnit.SECONDS));
        assertThat(u.isMessagingMuted(Instant.now())).isFalse();
    }
}
