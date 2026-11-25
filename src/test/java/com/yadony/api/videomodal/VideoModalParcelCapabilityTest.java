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
import static com.yadony.api.videomodal.VideoModalFixtures.parcelCampaign;
import static com.yadony.api.videomodal.VideoModalFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Capacité {@code parcel} : filtre des campagnes d'étape du colis et éligibilité ONCE_PER_PARCEL. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals : capacité parcel")
class VideoModalParcelCapabilityTest {

    private static final String NEW_APP = "layout,targeting,moments,everytime,parcel";
    private static final String MOMENTS_APP = "layout,targeting,moments,everytime";

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock UserRepository userRepository;
    @Mock VideoModalImageUrls imageUrls;
    @Mock VideoModalTargeting targeting;

    private final UUID userId = UUID.randomUUID();
    private VideoModalService service;

    @BeforeEach
    void setUp() {
        service = new VideoModalService(campaignRepository, responseRepository, userRepository,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), imageUrls, targeting,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void knownUser(List<VideoModalResponseEntity> responses) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(u));
        when(responseRepository.findByUserIdAndCampaignIdIn(eq(userId), anyCollection())).thenReturn(responses);
    }

    @Test
    @DisplayName("supportsParcel : jeton seul dans une liste, casse et espaces ignorés")
    void supportsParcel() {
        assertThat(VideoModalService.supportsParcel(NEW_APP)).isTrue();
        assertThat(VideoModalService.supportsParcel(" Parcel ")).isTrue();
        assertThat(VideoModalService.supportsParcel(MOMENTS_APP)).isFalse();
        assertThat(VideoModalService.supportsParcel("parcels")).isFalse();
        assertThat(VideoModalService.supportsParcel(null)).isFalse();
    }

    @Test
    @DisplayName("appWithoutParcelCapDoesNotReceiveParcelCampaign (visiteur et compte)")
    void appWithoutParcelCapDoesNotReceiveParcelCampaign() {
        var c = parcelCampaign(VideoModalTrigger.PARCEL_ARRIVED);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(c));

        assertThat(service.eligible(null, "fr", MOMENTS_APP)).isEmpty();
        // Compte : le filtre passe avant toute lecture des réponses, aucune requête utilisateur.
        assertThat(service.eligible("uid", "fr", MOMENTS_APP)).isEmpty();
    }

    @Test
    @DisplayName("appWithParcelCapReceivesIt : fréquence ONCE_PER_PARCEL, rôles et déclencheurs inchangés")
    void appWithParcelCapReceivesIt() {
        var c = parcelCampaign(VideoModalTrigger.PARCEL_ARRIVED, VideoModalTrigger.PARCEL_DELIVERED);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(c));
        knownUser(List.of());

        VideoModalEligibleResponse r = service.eligible("uid", "fr", NEW_APP).get(0);
        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.ONCE_PER_PARCEL);
        assertThat(r.parcelRoles()).containsExactlyInAnyOrder(VideoModalParcelRole.SENDER, VideoModalParcelRole.RECIPIENT);
        assertThat(r.triggers()).containsExactly(VideoModalTrigger.PARCEL_ARRIVED, VideoModalTrigger.PARCEL_DELIVERED);
    }

    @Test
    @DisplayName("oncePerParcelStaysEligibleAfterAnswer : réponse et plafond n'écartent jamais la campagne")
    void oncePerParcelStaysEligibleAfterAnswer() {
        var c = parcelCampaign(VideoModalTrigger.PARCEL_ARRIVED);
        assertThat(c.isStopAfterAnswer()).isTrue(); // défaut de l'entité : ne doit pas la faire disparaître

        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 40, VideoModalEvent.SHOWN)), NOW))
                .isEqualTo(40);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 3, VideoModalEvent.OPENED)), NOW))
                .isEqualTo(3);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 3, VideoModalEvent.DISMISSED)), NOW))
                .isEqualTo(3);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(), NOW)).isEqualTo(0);
    }

    @Test
    @DisplayName("oncePerParcel : « ne plus afficher » reste définitif, comme EVERY_TIME")
    void oncePerParcelRespectsDontShowAgain() {
        var c = parcelCampaign(VideoModalTrigger.PARCEL_ARRIVED);
        var dismissed = response(c, 1, 2, VideoModalEvent.SHOWN);
        dismissed.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(dismissed), NOW)).isNull();
    }

    @Test
    @DisplayName("contrôle : une campagne ordinaire à réponse unique reste écartée après une réponse")
    void stopAfterAnswerStillAppliesToOtherFrequencies() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setStopAfterAnswer(true);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(response(c, 1, 1, VideoModalEvent.OPENED)), NOW))
                .isNull();
    }

    @Test
    @DisplayName("parcelCampaignKeepsOtherTriggerOnlyForCapableApps : mixte étape + autre déclencheur, app moments sans parcel : absente")
    void parcelCampaignKeepsOtherTriggerOnlyForCapableApps() {
        var mixed = parcelCampaign(VideoModalTrigger.PARCEL_ARRIVED, VideoModalTrigger.DELIVERY_CONFIRMED);
        mixed.setFrequency(VideoModalFrequency.EVERY_TIME);
        mixed.setParcelRoles(java.util.Set.of());
        mixed.setPrimaryAction(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.PARCEL_QR, null));
        assertThat(mixed.needsParcelCapability()).isTrue();
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(mixed));

        assertThat(service.eligible(null, "fr", MOMENTS_APP)).isEmpty();
        assertThat(service.eligible(null, "fr", NEW_APP)).singleElement()
                .extracting(VideoModalEligibleResponse::triggers)
                .asList().contains(VideoModalTrigger.PARCEL_ARRIVED, VideoModalTrigger.DELIVERY_CONFIRMED);
    }

    @Test
    @DisplayName("oldAppsStillReceiveExistingCampaigns : campagne ordinaire servie avec ou sans parcel")
    void oldAppsStillReceiveExistingCampaigns() {
        var ordinary = campaign(1, VideoModalAudience.EVERYONE);
        var everyTime = campaign(1, VideoModalAudience.EVERYONE);
        everyTime.setTriggers(List.of(VideoModalTrigger.DELIVERY_CONFIRMED));
        everyTime.setFrequency(VideoModalFrequency.EVERY_TIME);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(ordinary, everyTime));

        assertThat(ordinary.needsParcelCapability()).isFalse();
        assertThat(service.eligible(null, "fr", null)).extracting(VideoModalEligibleResponse::id)
                .containsExactly(ordinary.getId());
        assertThat(service.eligible(null, "fr", MOMENTS_APP)).hasSize(2);
        assertThat(service.eligible(null, "fr", NEW_APP)).hasSize(2);
    }
}
