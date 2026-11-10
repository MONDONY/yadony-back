package com.yadony.api.videomodal;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static com.yadony.api.videomodal.VideoModalFixtures.infoCampaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Filtre de compatibilité {@code caps=layout} et nouveaux champs de la réponse app. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals : éligibilité avec et sans caps=layout")
class VideoModalLayoutEligibilityTest {

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock UserRepository userRepository;
    @Mock VideoModalImageUrls imageUrls;

    private VideoModalService service;

    @BeforeEach
    void setUp() {
        service = new VideoModalService(campaignRepository, responseRepository, userRepository,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), imageUrls, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static VideoModalCampaignEntity poster() {
        VideoModalCampaignEntity c = infoCampaign(1, VideoModalAudience.EVERYONE);
        c.setLayout(VideoModalLayout.FULLSCREEN);
        c.setImageMode(VideoModalImageMode.POSTER);
        c.setImageFit(VideoModalImageFit.CONTAIN);
        c.setImageKey("video-modals/x/1.png");
        c.setImageAltFr("Affiche FR");
        c.setImageAltEn("Poster EN");
        return c;
    }

    private void live(VideoModalCampaignEntity... campaigns) {
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(campaigns));
    }

    @Test
    @DisplayName("sans caps : les campagnes ordinaires (feuille du bas, sans image) sont envoyées, même avec une taille")
    void ordinaryWithoutCaps() {
        VideoModalCampaignEntity video = campaign(1, VideoModalAudience.EVERYONE);
        video.setSizePercent(60);
        VideoModalCampaignEntity info = infoCampaign(1, VideoModalAudience.EVERYONE);
        live(video, info);

        List<VideoModalEligibleResponse> r = service.eligible(null, "fr", null);

        assertThat(r).hasSize(2);
        assertThat(r.get(0).layout()).isEqualTo(VideoModalLayout.BOTTOM_SHEET);
        assertThat(r.get(0).sizePercent()).isEqualTo(60);
    }

    @Test
    @DisplayName("sans caps : carte centrée, plein écran et image sont retenus")
    void layoutAndImageHiddenWithoutCaps() {
        VideoModalCampaignEntity centered = campaign(1, VideoModalAudience.EVERYONE);
        centered.setLayout(VideoModalLayout.CENTERED);
        VideoModalCampaignEntity banner = infoCampaign(1, VideoModalAudience.EVERYONE);
        banner.setImageMode(VideoModalImageMode.TOP);
        banner.setImageKey("video-modals/x/1.png");
        VideoModalCampaignEntity ordinary = infoCampaign(1, VideoModalAudience.EVERYONE);
        live(centered, poster(), banner, ordinary);

        assertThat(service.eligible(null, "fr", null)).extracting(VideoModalEligibleResponse::id)
                .containsExactly(ordinary.getId());
        assertThat(service.eligible(null, "fr", "")).hasSize(1);
        assertThat(service.eligible(null, "fr", "autre")).hasSize(1);
        assertThat(service.eligible(null, "fr")).hasSize(1);
    }

    @Test
    @DisplayName("sans caps, un utilisateur connu est filtré de la même façon")
    void hiddenWithoutCapsForKnownUser() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(u));
        VideoModalCampaignEntity ordinary = infoCampaign(1, VideoModalAudience.EVERYONE);
        live(poster(), ordinary);
        when(responseRepository.findByUserIdAndCampaignIdIn(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of());

        assertThat(service.eligible("uid", "fr", null)).extracting(VideoModalEligibleResponse::id)
                .containsExactly(ordinary.getId());
        assertThat(service.eligible("uid", "fr", "layout")).hasSize(2);
    }

    @Test
    @DisplayName("caps=layout (casse et liste tolérées) : tout est envoyé avec format, taille et image")
    void withCaps() {
        when(imageUrls.urlOf("video-modals/x/1.png")).thenReturn("https://r2/signed");
        live(poster());

        for (String caps : List.of("layout", "LAYOUT", "other, layout ", "layout,other")) {
            List<VideoModalEligibleResponse> r = service.eligible(null, "fr", caps);
            assertThat(r).as(caps).hasSize(1);
        }
        VideoModalEligibleResponse r = service.eligible(null, "fr", "layout").get(0);
        assertThat(r.layout()).isEqualTo(VideoModalLayout.FULLSCREEN);
        assertThat(r.imageMode()).isEqualTo(VideoModalImageMode.POSTER);
        assertThat(r.imageFit()).isEqualTo(VideoModalImageFit.CONTAIN);
        assertThat(r.imageUrl()).isEqualTo("https://r2/signed");
        assertThat(r.imageAlt()).isEqualTo("Affiche FR");
    }

    @Test
    @DisplayName("texte alternatif : anglais si demandé, repli sur le titre si vide")
    void imageAlt() {
        when(imageUrls.urlOf("video-modals/x/1.png")).thenReturn("https://r2/signed");
        VideoModalCampaignEntity withAlt = poster();
        VideoModalCampaignEntity noAlt = poster();
        noAlt.setImageAltFr(null);
        noAlt.setImageAltEn(null);
        live(withAlt, noAlt);

        List<VideoModalEligibleResponse> en = service.eligible(null, "en", "layout");
        assertThat(en.get(0).imageAlt()).isEqualTo("Poster EN");
        assertThat(en.get(1).imageAlt()).isEqualTo("Your parcel is coming");
        assertThat(service.eligible(null, "fr", "layout").get(1).imageAlt()).isEqualTo("Votre colis arrive");
    }

    @Test
    @DisplayName("VIDEO : format et taille renvoyés, jamais de champ d'image")
    void videoHasNoImageFields() {
        VideoModalCampaignEntity v = campaign(1, VideoModalAudience.EVERYONE);
        v.setLayout(VideoModalLayout.CENTERED);
        v.setSizePercent(70);
        live(v);

        VideoModalEligibleResponse r = service.eligible(null, "fr", "layout").get(0);
        assertThat(r.layout()).isEqualTo(VideoModalLayout.CENTERED);
        assertThat(r.sizePercent()).isEqualTo(70);
        assertThat(r.imageMode()).isNull();
        assertThat(r.imageFit()).isNull();
        assertThat(r.imageUrl()).isNull();
        assertThat(r.imageAlt()).isNull();
    }

    @Test
    @DisplayName("INFO sans image : imageMode NONE, pas d'URL")
    void infoWithoutImage() {
        live(infoCampaign(1, VideoModalAudience.EVERYONE));
        VideoModalEligibleResponse r = service.eligible(null, "fr", "layout").get(0);
        assertThat(r.imageMode()).isEqualTo(VideoModalImageMode.NONE);
        assertThat(r.imageUrl()).isNull();
        assertThat(r.imageAlt()).isNull();
        assertThat(r.layout()).isEqualTo(VideoModalLayout.BOTTOM_SHEET);
        assertThat(r.sizePercent()).isNull();
    }

    @Test
    @DisplayName("supportsLayout : parsing de la liste")
    void supportsLayout() {
        assertThat(VideoModalService.supportsLayout(null)).isFalse();
        assertThat(VideoModalService.supportsLayout("")).isFalse();
        assertThat(VideoModalService.supportsLayout("layouts")).isFalse();
        assertThat(VideoModalService.supportsLayout("layout")).isTrue();
    }
}
