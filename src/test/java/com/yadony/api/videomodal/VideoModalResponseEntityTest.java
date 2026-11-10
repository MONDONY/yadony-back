package com.yadony.api.videomodal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalEvent.COMPLETED;
import static com.yadony.api.videomodal.VideoModalEvent.DISMISSED;
import static com.yadony.api.videomodal.VideoModalEvent.OPENED;
import static com.yadony.api.videomodal.VideoModalEvent.SHOWN;
import static org.assertj.core.api.Assertions.assertThat;

class VideoModalResponseEntityTest {

    private static final Instant T1 = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T11:00:00Z");

    private static VideoModalResponseEntity fresh() {
        return new VideoModalResponseEntity(UUID.randomUUID(), UUID.randomUUID(), 1);
    }

    @Test
    @DisplayName("SHOWN incrémente les affichages et garde la date la plus récente")
    void shownCompte() {
        var r = fresh();
        r.apply(SHOWN, T2);
        r.apply(SHOWN, T1);
        assertThat(r.getDisplays()).isEqualTo(2);
        assertThat(r.getLastShownAt()).isEqualTo(T2);
        assertThat(r.getStatus()).isEqualTo(SHOWN);
        assertThat(r.getAnsweredAt()).isNull();
    }

    @Test
    @DisplayName("statut jamais rétrogradé : COMPLETED > OPENED > DISMISSED > SHOWN")
    void jamaisRetrograde() {
        var r = fresh();
        r.apply(COMPLETED, T1);
        r.apply(OPENED, T2);
        r.apply(DISMISSED, T2);
        r.apply(SHOWN, T2);
        assertThat(r.getStatus()).isEqualTo(COMPLETED);
        assertThat(r.getAnsweredAt()).isEqualTo(T1);
        assertThat(r.getOpenedAt()).isEqualTo(T1);
        assertThat(r.getCompletedAt()).isEqualTo(T1);
        assertThat(r.getDisplays()).isEqualTo(1);
    }

    @Test
    @DisplayName("montée DISMISSED → OPENED → COMPLETED, dates posées une seule fois")
    void monteeEtIdempotence() {
        var r = fresh();
        r.apply(DISMISSED, T1);
        assertThat(r.getStatus()).isEqualTo(DISMISSED);
        assertThat(r.getOpenedAt()).isNull();
        r.apply(OPENED, T2);
        r.apply(OPENED, T2.plusSeconds(60));
        assertThat(r.getStatus()).isEqualTo(OPENED);
        assertThat(r.getAnsweredAt()).isEqualTo(T1);
        assertThat(r.getOpenedAt()).isEqualTo(T2);
        assertThat(r.getCompletedAt()).isNull();
        r.apply(COMPLETED, T2.plusSeconds(120));
        assertThat(r.getStatus()).isEqualTo(COMPLETED);
        assertThat(r.getOpenedAt()).isEqualTo(T2);
    }

    @Test
    void rangsEtCategories() {
        assertThat(SHOWN.isAnswer()).isFalse();
        assertThat(DISMISSED.isAnswer()).isTrue();
        assertThat(DISMISSED.isWatched()).isFalse();
        assertThat(OPENED.isWatched()).isTrue();
        assertThat(COMPLETED.isWatched()).isTrue();
        assertThat(COMPLETED.rank()).isGreaterThan(OPENED.rank());
        assertThat(OPENED.rank()).isGreaterThan(DISMISSED.rank());
        assertThat(DISMISSED.rank()).isGreaterThan(SHOWN.rank());
    }
}
