package com.yadony.api.videomodal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static org.assertj.core.api.Assertions.assertThat;

/** Fréquences, réponse, « ne plus afficher » et version/système : règles pures, sans base. */
@DisplayName("Modals : fréquence, réponse, « ne plus afficher », version et système")
class VideoModalTargetingEligibilityTest {

    private static VideoModalResponseEntity row(VideoModalCampaignEntity c, int version) {
        return new VideoModalResponseEntity(c.getId(), UUID.randomUUID(), version);
    }

    // ── Défauts : comportement d'avant ─────────────────────────────────────

    @Test
    @DisplayName("défauts d'une campagne existante : UP_TO_N, arrêt après réponse, aucun ciblage")
    void defauts() {
        var c = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        assertThat(c.getFrequency()).isEqualTo(VideoModalFrequency.UP_TO_N);
        assertThat(c.isStopAfterAnswer()).isTrue();
        assertThat(c.isAllowDontShowAgain()).isFalse();
        assertThat(c.getPriority()).isZero();
        assertThat(c.isConnectedOnly()).isFalse();
        assertThat(c.hasConditions()).isFalse();
        assertThat(c.getSituations()).isEmpty();
        assertThat(c.getPlatforms()).isEmpty();
        assertThat(c.getSamplePercent()).isEqualTo(100);
        assertThat(c.needsAccount()).isFalse();
        assertThat(c.needsTargetingCapability()).isFalse();
        assertThat(c.displayCap()).isEqualTo(3);
        assertThat(c.effectiveTriggers()).containsExactly(VideoModalTrigger.SEARCH_TAB);
    }

    // ── Fréquences ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("ONCE : un seul affichage par version, comme UP_TO_N avec 1")
    void once() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setFrequency(VideoModalFrequency.ONCE);
        c.setMaxDisplays(5);
        var r = row(c, 1);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(), NOW)).isZero();
        r.apply(VideoModalEvent.SHOWN, NOW);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW)).isNull();
        c.setVersion(2); // nouvelle version : la ligne de la v1 ne compte plus
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW)).isZero();
    }

    @Test
    @DisplayName("EVERY_OPEN : jamais de plafond d'affichages")
    void everyOpen() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setFrequency(VideoModalFrequency.EVERY_OPEN);
        c.setMaxDisplays(1);
        var r = row(c, 1);
        for (int i = 0; i < 50; i++) r.apply(VideoModalEvent.SHOWN, NOW);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW)).isEqualTo(50);
        assertThat(c.displayCap()).isNull();
    }

    @Test
    @DisplayName("EVERY_N_DAYS : exactement N jours après le dernier affichage = éligible, une seconde de moins = non")
    void everyNDaysLimites() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setFrequency(VideoModalFrequency.EVERY_N_DAYS);
        c.setFrequencyDays(7);
        var r = row(c, 1);
        r.apply(VideoModalEvent.SHOWN, NOW.minus(Duration.ofDays(7)));
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW)).isEqualTo(1);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW.minusSeconds(1))).isNull();
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW.plus(Duration.ofDays(30)))).isEqualTo(1);
    }

    @Test
    @DisplayName("EVERY_N_DAYS : jamais montrée = éligible ; pas de plafond d'affichages")
    void everyNDaysJamaisMontree() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setFrequency(VideoModalFrequency.EVERY_N_DAYS);
        c.setFrequencyDays(1);
        c.setMaxDisplays(1);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(), NOW)).isZero();
        var r = row(c, 1);
        r.apply(VideoModalEvent.SHOWN, NOW.minus(Duration.ofDays(2)));
        r.apply(VideoModalEvent.SHOWN, NOW.minus(Duration.ofDays(1)));
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW)).isEqualTo(2);
    }

    // ── Réponse ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stopAfterAnswer = false : une réponse ne bloque pas, seuls plafond et fréquence jugent")
    void sansArretApresReponse() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setStopAfterAnswer(false);
        var r = row(c, 1);
        r.apply(VideoModalEvent.SHOWN, NOW);
        r.apply(VideoModalEvent.OPENED, NOW);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW)).isEqualTo(1);
        r.apply(VideoModalEvent.SHOWN, NOW);
        r.apply(VideoModalEvent.SHOWN, NOW);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW)).isNull(); // plafond 3 atteint
    }

    @Test
    @DisplayName("stopAfterAnswer = true (défaut) : OPENED, COMPLETED et DISMISSED bloquent")
    void arretApresReponse() {
        for (VideoModalEvent answer : List.of(VideoModalEvent.OPENED, VideoModalEvent.COMPLETED, VideoModalEvent.DISMISSED)) {
            var c = campaign(1, VideoModalAudience.EVERYONE);
            var r = row(c, 1);
            r.apply(VideoModalEvent.SHOWN, NOW);
            r.apply(answer, NOW);
            assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW)).as(answer.name()).isNull();
        }
    }

    // ── « Ne plus afficher » ───────────────────────────────────────────────

    @Test
    @DisplayName("DONT_SHOW_AGAIN : définitif pour la version, quels que soient fréquence et stopAfterAnswer")
    void neplusAfficher() {
        for (VideoModalFrequency f : VideoModalFrequency.values()) {
            var c = campaign(1, VideoModalAudience.EVERYONE);
            c.setFrequency(f);
            c.setFrequencyDays(1);
            c.setStopAfterAnswer(false);
            var r = row(c, 1);
            r.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
            assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(r), NOW.plus(Duration.ofDays(400))))
                    .as(f.name()).isNull();
        }
    }

    @Test
    @DisplayName("DONT_SHOW_AGAIN d'une version ne bloque pas la version suivante")
    void neplusAfficherParVersion() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        var v1 = row(c, 1);
        v1.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
        c.setVersion(2);
        assertThat(VideoModalEligibility.displaysIfEligible(c, List.of(v1), NOW)).isZero();
    }

    // ── Version et système ─────────────────────────────────────────────────

    @Test
    @DisplayName("version : bornes incluses, version inconnue exclue dès qu'une borne existe")
    void versions() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        assertThat(VideoModalEligibility.matchesClient(c, null, null)).isTrue(); // aucune restriction
        c.setMinAppVersion("1.0.6");
        assertThat(VideoModalEligibility.matchesClient(c, new int[] {1, 0, 6}, null)).isTrue();
        assertThat(VideoModalEligibility.matchesClient(c, new int[] {1, 0, 5}, null)).isFalse();
        assertThat(VideoModalEligibility.matchesClient(c, new int[] {1, 0, 10}, null)).isTrue();
        assertThat(VideoModalEligibility.matchesClient(c, null, null)).isFalse();
        c.setMaxAppVersion("1.1.0");
        assertThat(VideoModalEligibility.matchesClient(c, new int[] {1, 1, 0}, null)).isTrue();
        assertThat(VideoModalEligibility.matchesClient(c, new int[] {1, 1, 1}, null)).isFalse();
        c.setMinAppVersion(null);
        assertThat(VideoModalEligibility.matchesClient(c, new int[] {0, 9, 0}, null)).isTrue();
        assertThat(VideoModalEligibility.matchesClient(c, null, null)).isFalse();
    }

    @Test
    @DisplayName("système : liste vide = tous ; sinon il faut le bon système connu")
    void plateformes() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        assertThat(VideoModalEligibility.matchesClient(c, null, null)).isTrue();
        c.setPlatforms(List.of(VideoModalPlatform.IOS));
        assertThat(VideoModalEligibility.matchesClient(c, null, VideoModalPlatform.IOS)).isTrue();
        assertThat(VideoModalEligibility.matchesClient(c, null, VideoModalPlatform.ANDROID)).isFalse();
        assertThat(VideoModalEligibility.matchesClient(c, null, null)).isFalse();
        c.setPlatforms(List.of(VideoModalPlatform.IOS, VideoModalPlatform.ANDROID));
        assertThat(VideoModalEligibility.matchesClient(c, null, VideoModalPlatform.ANDROID)).isTrue();
    }

    @Test
    @DisplayName("système lu depuis le paramètre : android/ios sans casse, le reste est inconnu")
    void plateformeParam() {
        assertThat(VideoModalPlatform.fromParam("android")).isEqualTo(VideoModalPlatform.ANDROID);
        assertThat(VideoModalPlatform.fromParam(" IOS ")).isEqualTo(VideoModalPlatform.IOS);
        assertThat(VideoModalPlatform.fromParam("web")).isNull();
        assertThat(VideoModalPlatform.fromParam(null)).isNull();
    }

    // ── Entité ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("needsAccount : conditions, situations, tirage < 100 ou connectedOnly")
    void needsAccount() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setConnectedOnly(true);
        assertThat(c.needsAccount()).isTrue();
        c.setConnectedOnly(false);
        c.setSamplePercent(99);
        assertThat(c.needsAccount()).isTrue();
        c.setSamplePercent(100);
        c.setConditions("{\"op\":\"AND\",\"children\":[]}");
        assertThat(c.needsAccount()).isTrue();
        c.setConditions("  ");
        assertThat(c.needsAccount()).isFalse();
        c.setSituations(List.of(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null)));
        assertThat(c.needsAccount()).isTrue();
    }

    @Test
    @DisplayName("situations sans déclencheur : servie avec APP_OPEN ; avec déclencheurs : inchangés")
    void declencheurParDefaut() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setTriggers(List.of());
        assertThat(c.effectiveTriggers()).isEmpty();
        c.setSituations(List.of(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null)));
        assertThat(c.effectiveTriggers()).containsExactly(VideoModalTrigger.APP_OPEN);
        c.setTriggers(List.of(VideoModalTrigger.PROFILE_TAB));
        assertThat(c.effectiveTriggers()).containsExactly(VideoModalTrigger.PROFILE_TAB);
    }

    @Test
    @DisplayName("needsTargetingCapability : EVERY_OPEN, EVERY_N_DAYS ou allowDontShowAgain")
    void capaciteTargeting() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        for (VideoModalFrequency f : List.of(VideoModalFrequency.ONCE, VideoModalFrequency.UP_TO_N)) {
            c.setFrequency(f);
            assertThat(c.needsTargetingCapability()).isFalse();
        }
        c.setAllowDontShowAgain(true);
        assertThat(c.needsTargetingCapability()).isTrue();
        c.setAllowDontShowAgain(false);
        c.setFrequency(VideoModalFrequency.EVERY_OPEN);
        assertThat(c.needsTargetingCapability()).isTrue();
        c.setFrequency(VideoModalFrequency.EVERY_N_DAYS);
        assertThat(c.needsTargetingCapability()).isTrue();
        c.setFrequency(null);
        assertThat(c.getFrequency()).isEqualTo(VideoModalFrequency.UP_TO_N);
    }

    @Test
    @DisplayName("current : la ligne de la version courante, ou rien")
    void current() {
        var c = campaign(2, VideoModalAudience.EVERYONE);
        var v1 = row(c, 1);
        var v2 = row(c, 2);
        assertThat(VideoModalEligibility.current(c, List.of(v1, v2))).containsSame(v2);
        assertThat(VideoModalEligibility.current(c, List.of(v1))).isEmpty();
        Instant any = VideoModalEligibility.current(c, List.of()).map(VideoModalResponseEntity::getLastShownAt).orElse(null);
        assertThat(any).isNull();
    }
}
