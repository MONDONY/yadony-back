package com.yadony.api.videomodal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DONT_SHOW_AGAIN : un drapeau, ni statut ni affichage")
class VideoModalDontShowAgainTest {

    private final VideoModalResponseEntity row = new VideoModalResponseEntity(UUID.randomUUID(), UUID.randomUUID(), 1);

    @Test
    @DisplayName("pose le drapeau sans changer statut, compteur ni dates de réponse")
    void drapeau() {
        row.apply(VideoModalEvent.SHOWN, NOW);
        row.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW.plusSeconds(5));

        assertThat(row.isDontShowAgain()).isTrue();
        assertThat(row.getDontShowAgainAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(row.getStatus()).isEqualTo(VideoModalEvent.SHOWN);
        assertThat(row.getDisplays()).isEqualTo(1);
        assertThat(row.getAnsweredAt()).isNull();
        assertThat(row.getLastShownAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("rejoué : la première date est conservée")
    void idempotent() {
        row.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
        row.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW.plusSeconds(60));
        assertThat(row.getDontShowAgainAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("seul (sans SHOWN préalable) : crée un état propre, jamais un statut DONT_SHOW_AGAIN")
    void seul() {
        row.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
        assertThat(row.getStatus()).isEqualTo(VideoModalEvent.SHOWN);
        assertThat(row.getDisplays()).isZero();
    }

    @Test
    @DisplayName("compatible avec une réponse du bouton, dans n'importe quel ordre")
    void avecReponse() {
        row.apply(VideoModalEvent.OPENED, NOW);
        row.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
        assertThat(row.getStatus()).isEqualTo(VideoModalEvent.OPENED);
        VideoModalResponseEntity other = new VideoModalResponseEntity(UUID.randomUUID(), UUID.randomUUID(), 1);
        other.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
        other.apply(VideoModalEvent.DISMISSED, NOW);
        assertThat(other.getStatus()).isEqualTo(VideoModalEvent.DISMISSED);
        assertThat(other.isDontShowAgain()).isTrue();
    }

    @Test
    @DisplayName("l'événement n'est ni une réponse ni une vidéo vue")
    void evenement() {
        assertThat(VideoModalEvent.DONT_SHOW_AGAIN.isAnswer()).isFalse();
        assertThat(VideoModalEvent.DONT_SHOW_AGAIN.isWatched()).isFalse();
        assertThat(VideoModalEvent.DONT_SHOW_AGAIN.rank()).isNegative();
        assertThat(VideoModalEvent.SHOWN.isAnswer()).isFalse();
        assertThat(VideoModalEvent.DISMISSED.isAnswer()).isTrue();
        assertThat(VideoModalEvent.OPENED.isAnswer()).isTrue();
        assertThat(VideoModalEvent.COMPLETED.isAnswer()).isTrue();
    }

    @Test
    @DisplayName("aucun Instant nul accepté pour la date : la date d'événement est posée telle quelle")
    void date() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        row.apply(VideoModalEvent.DONT_SHOW_AGAIN, at);
        assertThat(row.getDontShowAgainAt()).isEqualTo(at);
    }
}
