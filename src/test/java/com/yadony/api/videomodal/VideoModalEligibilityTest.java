package com.yadony.api.videomodal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static com.yadony.api.videomodal.VideoModalAudience.EVERYONE;
import static com.yadony.api.videomodal.VideoModalAudience.NEVER_ANSWERED;
import static com.yadony.api.videomodal.VideoModalAudience.NOT_WATCHED_PREVIOUS;
import static com.yadony.api.videomodal.VideoModalEvent.COMPLETED;
import static com.yadony.api.videomodal.VideoModalEvent.DISMISSED;
import static com.yadony.api.videomodal.VideoModalEvent.OPENED;
import static com.yadony.api.videomodal.VideoModalEvent.SHOWN;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static com.yadony.api.videomodal.VideoModalFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;

class VideoModalEligibilityTest {

    @ParameterizedTest
    @EnumSource(VideoModalAudience.class)
    @DisplayName("aucune réponse : éligible pour tous les publics, 0 affichage")
    void sansReponse(VideoModalAudience audience) {
        var c = campaign(2, audience);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of())).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = VideoModalEvent.class, names = {"OPENED", "COMPLETED", "DISMISSED"})
    @DisplayName("réponse à la version courante : exclu, même pour EVERYONE")
    void reponduALaVersionCourante(VideoModalEvent status) {
        var c = campaign(2, EVERYONE);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 2, 1, status)))).isNull();
    }

    @Test
    @DisplayName("plafond d'affichages : exclu à maxDisplays, éligible juste en dessous")
    void plafondDAffichages() {
        var c = campaign(1, NEVER_ANSWERED);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 2, SHOWN)))).isEqualTo(2);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 3, SHOWN)))).isNull();
        c.setMaxDisplays(5);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 3, SHOWN)))).isEqualTo(3);
    }

    @Test
    @DisplayName("les affichages d'une ancienne version ne comptent pas pour la nouvelle")
    void affichagesParVersion() {
        var c = campaign(2, EVERYONE);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 3, SHOWN)))).isZero();
    }

    @Test
    @DisplayName("NEVER_ANSWERED : exclu si une version précédente a reçu une réponse, pas si elle a seulement été vue")
    void neverAnswered() {
        var c = campaign(3, NEVER_ANSWERED);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 3, SHOWN)))).isZero();
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 1, DISMISSED)))).isNull();
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 2, 1, OPENED)))).isNull();
    }

    @Test
    @DisplayName("EVERYONE : éligible même après des réponses aux versions précédentes")
    void everyone() {
        var c = campaign(3, EVERYONE);
        assertThat(VideoModalEligibility.displaysIfEligible(c,
                List.of(response(c, 1, 1, COMPLETED), response(c, 2, 1, DISMISSED), response(c, 3, 1, SHOWN))))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("NOT_WATCHED_PREVIOUS : exclu si une vidéo précédente a été ouverte, éligible si seulement écartée")
    void notWatchedPrevious() {
        var c = campaign(3, NOT_WATCHED_PREVIOUS);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 1, DISMISSED)))).isZero();
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 1, OPENED)))).isNull();
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 2, 1, COMPLETED)))).isNull();
    }
}
