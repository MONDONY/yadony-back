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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Compatibilité {@code caps=moments} et données servies à l'app pour les moments d'action. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals : moments d'action et caps=moments (service)")
class VideoModalMomentsServiceTest {

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock UserRepository userRepository;
    @Mock VideoModalImageUrls imageUrls;
    @Mock VideoModalTargeting targeting;

    private final UUID userId = UUID.randomUUID();
    private final TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private VideoModalService service;

    @BeforeEach
    void setUp() {
        service = new VideoModalService(campaignRepository, responseRepository, userRepository, tx, imageUrls,
                targeting, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void live(VideoModalCampaignEntity... campaigns) {
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(campaigns));
    }

    private void knownUser() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(u));
        when(responseRepository.findByUserIdAndCampaignIdIn(eq(userId), anyCollection())).thenReturn(List.of());
    }

    private static VideoModalCampaignEntity with(VideoModalCampaignEntity c, VideoModalTrigger... triggers) {
        c.setTriggers(List.of(triggers));
        return c;
    }

    // ── caps ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("supportsMoments : « moments » seul, dans une liste, casse et espaces ignorés ; sinon non")
    void supportsMoments() {
        assertThat(VideoModalService.supportsMoments("layout,targeting,moments")).isTrue();
        assertThat(VideoModalService.supportsMoments(" Moments ")).isTrue();
        assertThat(VideoModalService.supportsMoments("layout,targeting")).isFalse();
        assertThat(VideoModalService.supportsMoments("moment")).isFalse();
        assertThat(VideoModalService.supportsMoments(null)).isFalse();
    }

    @Test
    @DisplayName("sans caps=moments : les nouveaux déclencheurs sont retirés des triggers servis")
    void retraitDesDeclencheurs() {
        var c = with(campaign(1, VideoModalAudience.EVERYONE), VideoModalTrigger.SEARCH_TAB,
                VideoModalTrigger.DELIVERY_CONFIRMED, VideoModalTrigger.SEARCH_FEW_RESULTS);
        live(c);

        for (String caps : new String[] {null, "", "layout", "layout,targeting"}) {
            assertThat(service.eligible(null, "fr", caps)).singleElement()
                    .extracting(VideoModalEligibleResponse::triggers)
                    .isEqualTo(List.of(VideoModalTrigger.SEARCH_TAB));
        }
    }

    @Test
    @DisplayName("campagne mixte SEARCH_TAB + DELIVERY_CONFIRMED : vue par une ancienne app = SEARCH_TAB seul ; nouvelle app = les deux")
    void campagneMixte() {
        var c = with(campaign(1, VideoModalAudience.EVERYONE), VideoModalTrigger.SEARCH_TAB,
                VideoModalTrigger.DELIVERY_CONFIRMED);
        live(c);
        knownUser();

        assertThat(service.eligible("uid", "fr", "layout,targeting").get(0).triggers())
                .containsExactly(VideoModalTrigger.SEARCH_TAB);
        assertThat(service.eligible("uid", "fr", "layout,targeting,moments").get(0).triggers())
                .containsExactly(VideoModalTrigger.SEARCH_TAB, VideoModalTrigger.DELIVERY_CONFIRMED);
    }

    @Test
    @DisplayName("campagne uniquement moments : non envoyée sans caps=moments (visiteur et compte), envoyée avec")
    void uniquementMoments() {
        var c = with(campaign(1, VideoModalAudience.EVERYONE), VideoModalTrigger.PAYMENT_FAILED,
                VideoModalTrigger.APP_UPDATED);
        live(c);

        assertThat(service.eligible(null, "fr", "layout,targeting")).isEmpty();
        assertThat(service.eligible(null, "fr", "layout,targeting,moments")).singleElement()
                .extracting(VideoModalEligibleResponse::triggers)
                .isEqualTo(List.of(VideoModalTrigger.PAYMENT_FAILED, VideoModalTrigger.APP_UPDATED));
        knownUser();
        assertThat(service.eligible("uid", "fr", "layout,targeting")).isEmpty();
        assertThat(service.eligible("uid", "fr", "layout,targeting,moments")).hasSize(1);
    }

    @Test
    @DisplayName("seul le retrait filtre : une campagne ancienne reste servie, inchangée, sans aucun cap")
    void nonRegression() {
        var old = with(campaign(1, VideoModalAudience.EVERYONE), VideoModalTrigger.APP_OPEN, VideoModalTrigger.PUBLISH_TRIP,
                VideoModalTrigger.SEND_PARCEL);
        var info = with(infoCampaign(1, VideoModalAudience.EVERYONE), VideoModalTrigger.TRACKING_TAB);
        live(old, info);

        List<VideoModalEligibleResponse> out = service.eligible(null, "fr", null);

        assertThat(out).extracting(VideoModalEligibleResponse::triggers).containsExactly(
                List.of(VideoModalTrigger.APP_OPEN, VideoModalTrigger.PUBLISH_TRIP, VideoModalTrigger.SEND_PARCEL),
                List.of(VideoModalTrigger.TRACKING_TAB));
    }

    @Test
    @DisplayName("situations sans déclencheur : servie avec APP_OPEN même sans caps=moments")
    void situationsSansDeclencheur() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setTriggers(List.of());
        c.setSituations(List.of(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null)));
        live(c);
        knownUser();
        when(targeting.holds(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null), userId))
                .thenReturn(true);

        assertThat(service.eligible("uid", "fr", "layout,targeting")).singleElement()
                .extracting(VideoModalEligibleResponse::triggers).isEqualTo(List.of(VideoModalTrigger.APP_OPEN));
    }

    // ── triggerOptions et corridors servis ─────────────────────────────────

    @Test
    @DisplayName("chaque entrée porte triggerOptions et corridors (défauts : 2, 3, liste vide), VIDEO comme INFO, visiteur comme compte")
    void champsServis() {
        var video = with(campaign(1, VideoModalAudience.EVERYONE), VideoModalTrigger.SEARCH_FEW_RESULTS);
        video.setTriggerOptions(new VideoModalTriggerOptions(6, 5));
        video.setCorridors(List.of(new VideoModalCorridor("Paris", "Abidjan"), new VideoModalCorridor(null, "Sénégal")));
        var info = with(infoCampaign(1, VideoModalAudience.EVERYONE), VideoModalTrigger.SEARCH_TAB);
        live(video, info);

        for (boolean connected : new boolean[] {false, true}) {
            if (connected) knownUser();
            List<VideoModalEligibleResponse> out = service.eligible(connected ? "uid" : null, "fr", "layout,targeting,moments");
            assertThat(out).hasSize(2);
            assertThat(out.get(0).triggerOptions()).isEqualTo(new VideoModalTriggerOptions(6, 5));
            assertThat(out.get(0).corridors()).containsExactly(new VideoModalCorridor("Paris", "Abidjan"),
                    new VideoModalCorridor(null, "Sénégal"));
            assertThat(out.get(1).triggerOptions()).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
            assertThat(out.get(1).corridors()).isEmpty();
        }
    }

    @Test
    @DisplayName("sans caps=moments, triggerOptions et corridors sont tout de même servis (inoffensifs pour l'ancienne app)")
    void servisSansCaps() {
        var c = with(campaign(1, VideoModalAudience.EVERYONE), VideoModalTrigger.SEARCH_TAB);
        c.setCorridors(List.of(new VideoModalCorridor("Lyon", null)));
        live(c);

        VideoModalEligibleResponse r = service.eligible(null, "fr", null).get(0);
        assertThat(r.corridors()).containsExactly(new VideoModalCorridor("Lyon", null));
        assertThat(r.triggerOptions()).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
    }

    @Test
    @DisplayName("les anciens constructeurs et withTargeting de la réponse donnent seuils par défaut et aucun corridor")
    void anciensConstructeursReponse() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        VideoModalEligibleResponse base = VideoModalTexts.toEligible(c, false, 0, imageUrls);
        assertThat(base.triggerOptions()).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
        assertThat(base.corridors()).isEmpty();

        var legacy = new VideoModalEligibleResponse(base.id(), base.type(), base.version(), base.triggers(), base.title(),
                base.subtitle(), base.youtubeVideoId(), base.durationSeconds(), base.highlights(), base.primaryLabel(),
                base.secondaryLabel(), base.body(), base.icon(), base.primaryAction(), base.secondaryAction(),
                base.maxDisplays(), base.displays(), base.audience(), base.layout(), base.sizePercent(),
                base.imageMode(), base.imageFit(), base.imageUrl(), base.imageAlt());
        assertThat(legacy.triggerOptions()).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
        assertThat(legacy.corridors()).isEmpty();
        assertThat(legacy.frequency()).isEqualTo(VideoModalFrequency.UP_TO_N);

        var withFrequency = new VideoModalEligibleResponse(base.id(), base.type(), base.version(), base.triggers(),
                base.title(), base.subtitle(), base.youtubeVideoId(), base.durationSeconds(), base.highlights(),
                base.primaryLabel(), base.secondaryLabel(), base.body(), base.icon(), base.primaryAction(),
                base.secondaryAction(), base.maxDisplays(), base.displays(), base.audience(), base.layout(),
                base.sizePercent(), base.imageMode(), base.imageFit(), base.imageUrl(), base.imageAlt(), 3,
                VideoModalFrequency.EVERY_OPEN, null, false, true, null);
        assertThat(withFrequency.priority()).isEqualTo(3);
        assertThat(withFrequency.corridors()).isEmpty();
        assertThat(withFrequency.withMoments(List.of(VideoModalTrigger.APP_UPDATED), new VideoModalTriggerOptions(4, 4),
                List.of(new VideoModalCorridor("A", "B")))).satisfies(r -> {
            assertThat(r.triggers()).containsExactly(VideoModalTrigger.APP_UPDATED);
            assertThat(r.priority()).isEqualTo(3);
            assertThat(r.frequency()).isEqualTo(VideoModalFrequency.EVERY_OPEN);
        });
    }
}
