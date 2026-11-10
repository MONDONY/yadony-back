package com.yadony.api.videomodal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Moments d'action : déclencheurs, seuils, corridors et filtre de compatibilité de l'entité. */
@DisplayName("Modals : moments d'action (déclencheurs, seuils, corridors)")
class VideoModalMomentsTest {

    private static final List<VideoModalTrigger> OLD = List.of(VideoModalTrigger.APP_OPEN, VideoModalTrigger.SEARCH_TAB,
            VideoModalTrigger.ACTIVITIES_TAB, VideoModalTrigger.TRACKING_TAB, VideoModalTrigger.MESSAGES_TAB,
            VideoModalTrigger.PROFILE_TAB, VideoModalTrigger.PUBLISH_TRIP, VideoModalTrigger.SEND_PARCEL);

    @Test
    @DisplayName("les huit déclencheurs d'avant restent les huit premiers, sans capacité requise")
    void anciensDeclencheurs() {
        assertThat(List.of(VideoModalTrigger.values()).subList(0, 8)).containsExactlyElementsOf(OLD);
        for (VideoModalTrigger t : OLD) {
            assertThat(t.needsMomentsCapability()).as(t.name()).isFalse();
            assertThat(t.usesCorridors()).as(t.name()).isFalse();
        }
    }

    @Test
    @DisplayName("les treize nouveaux moments exigent caps=moments")
    void nouveauxMoments() {
        List<VideoModalTrigger> news = List.of(VideoModalTrigger.values()).subList(8, VideoModalTrigger.values().length);
        assertThat(news).extracting(Enum::name).containsExactly(
                "DELIVERY_CONFIRMED", "PAYMENT_SUCCEEDED", "TRIP_PUBLISHED", "REQUEST_PUBLISHED", "KYC_VERIFIED",
                "SEARCH_NO_TRIP_RESULTS", "SEARCH_NO_PARCEL_RESULTS", "SEARCH_FEW_RESULTS", "FAVORITE_ADDED",
                "FORM_ABANDONED", "TRIP_VIEWED_REPEATEDLY", "PAYMENT_FAILED", "APP_UPDATED");
        news.forEach(t -> assertThat(t.needsMomentsCapability()).as(t.name()).isTrue());
    }

    @Test
    @DisplayName("le ciblage par corridor ne vaut que pour recherche, favoris, consultation, formulaire et publications")
    void corridors() {
        assertThat(List.of(VideoModalTrigger.values()).stream().filter(VideoModalTrigger::usesCorridors))
                .containsExactlyInAnyOrder(VideoModalTrigger.SEARCH_NO_TRIP_RESULTS,
                        VideoModalTrigger.SEARCH_NO_PARCEL_RESULTS, VideoModalTrigger.SEARCH_FEW_RESULTS,
                        VideoModalTrigger.FAVORITE_ADDED, VideoModalTrigger.FORM_ABANDONED,
                        VideoModalTrigger.TRIP_VIEWED_REPEATEDLY, VideoModalTrigger.TRIP_PUBLISHED,
                        VideoModalTrigger.REQUEST_PUBLISHED);
    }

    @Test
    @DisplayName("seuils par défaut : 2 résultats, 3 consultations ; bornes publiques")
    void seuilsParDefaut() {
        assertThat(VideoModalTriggerOptions.DEFAULTS).isEqualTo(new VideoModalTriggerOptions(2, 3));
        assertThat(new VideoModalCampaignEntity().getTriggerOptions()).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
        assertThat(new VideoModalCampaignEntity().getCorridors()).isEmpty();
    }

    @Test
    @DisplayName("setTriggerOptions et setCorridors : null redevient défaut / liste vide, copie défensive")
    void accesseurs() {
        var c = new VideoModalCampaignEntity();
        c.setTriggerOptions(new VideoModalTriggerOptions(5, 4));
        assertThat(c.getTriggerOptions()).isEqualTo(new VideoModalTriggerOptions(5, 4));
        c.setTriggerOptions(null);
        assertThat(c.getTriggerOptions()).isEqualTo(VideoModalTriggerOptions.DEFAULTS);

        List<VideoModalCorridor> source = new java.util.ArrayList<>(List.of(new VideoModalCorridor("Paris", null)));
        c.setCorridors(source);
        source.clear();
        assertThat(c.getCorridors()).containsExactly(new VideoModalCorridor("Paris", null));
        c.setCorridors(null);
        assertThat(c.getCorridors()).isEmpty();
    }

    @Test
    @DisplayName("servedTriggers : avec moments tout est servi ; sans, les nouveaux moments sont retirés")
    void servedTriggers() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setTriggers(List.of(VideoModalTrigger.SEARCH_TAB, VideoModalTrigger.DELIVERY_CONFIRMED,
                VideoModalTrigger.APP_UPDATED));

        assertThat(c.servedTriggers(true)).containsExactly(VideoModalTrigger.SEARCH_TAB,
                VideoModalTrigger.DELIVERY_CONFIRMED, VideoModalTrigger.APP_UPDATED);
        assertThat(c.servedTriggers(false)).containsExactly(VideoModalTrigger.SEARCH_TAB);

        c.setTriggers(List.of(VideoModalTrigger.PAYMENT_FAILED));
        assertThat(c.servedTriggers(false)).isEmpty();
        assertThat(c.servedTriggers(true)).containsExactly(VideoModalTrigger.PAYMENT_FAILED);
    }

    @Test
    @DisplayName("servedTriggers : une campagne à situations sans déclencheur garde APP_OPEN, même sans moments")
    void servedTriggersSituations() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setTriggers(List.of());
        c.setSituations(List.of(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null)));
        assertThat(c.servedTriggers(false)).containsExactly(VideoModalTrigger.APP_OPEN);
        assertThat(c.servedTriggers(true)).containsExactly(VideoModalTrigger.APP_OPEN);
    }

    @Test
    @DisplayName("convertisseurs : seuils et corridors (côté nul compris) font l'aller-retour JSON ; vide = défauts")
    void convertisseurs() {
        var options = new VideoModalJsonConverters.TriggerOptions();
        String json = options.convertToDatabaseColumn(new VideoModalTriggerOptions(7, 5));
        assertThat(json).isEqualTo("{\"fewResultsMax\":7,\"tripViewedTimes\":5}");
        assertThat(options.convertToEntityAttribute(json)).isEqualTo(new VideoModalTriggerOptions(7, 5));
        assertThat(options.convertToDatabaseColumn(null)).isEqualTo("{\"fewResultsMax\":2,\"tripViewedTimes\":3}");
        assertThat(options.convertToEntityAttribute(null)).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
        assertThat(options.convertToEntityAttribute("  ")).isEqualTo(VideoModalTriggerOptions.DEFAULTS);
        assertThatThrownBy(() -> options.convertToEntityAttribute("{pas du json"))
                .isInstanceOf(IllegalStateException.class);

        var corridors = new VideoModalJsonConverters.Corridors();
        List<VideoModalCorridor> list = List.of(new VideoModalCorridor("Paris", "Abidjan"),
                new VideoModalCorridor(null, "Sénégal"));
        assertThat(corridors.convertToEntityAttribute(corridors.convertToDatabaseColumn(list))).isEqualTo(list);
        assertThat(corridors.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(corridors.convertToEntityAttribute("[]")).isEmpty();
        assertThat(corridors.convertToEntityAttribute(null)).isEmpty();
    }
}
