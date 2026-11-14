package com.yadony.api.videomodal;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import com.yadony.api.videomodal.dto.VideoModalEventRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static com.yadony.api.videomodal.VideoModalFixtures.infoCampaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Éligibilité de bout en bout du service : tous les filtres de l'étape « moment, ciblage, fréquence ». */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals : ciblage, fréquence et compatibilité (service)")
class VideoModalTargetingServiceTest {

    private static final String LANG_FR = "{\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]}],\"op\":\"AND\"}";

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
        service = serviceAt(NOW);
    }

    private VideoModalService serviceAt(Instant now) {
        return new VideoModalService(campaignRepository, responseRepository, userRepository, tx, imageUrls,
                targeting, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void live(VideoModalCampaignEntity... campaigns) {
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(campaigns));
    }

    private void knownUser() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(u));
    }

    private VideoModalResponseEntity shown(VideoModalCampaignEntity c, Instant at) {
        VideoModalResponseEntity r = new VideoModalResponseEntity(c.getId(), userId, c.getVersion());
        r.apply(VideoModalEvent.SHOWN, at);
        return r;
    }

    private void responses(VideoModalResponseEntity... rows) {
        when(responseRepository.findByUserIdAndCampaignIdIn(eq(userId), anyCollection())).thenReturn(List.of(rows));
    }

    private static List<UUID> ids(List<VideoModalEligibleResponse> list) {
        return list.stream().map(VideoModalEligibleResponse::id).toList();
    }

    // ── Compatibilité : caps=targeting ─────────────────────────────────────

    @Test
    @DisplayName("sans caps=targeting : EVERY_OPEN, EVERY_N_DAYS et allowDontShowAgain exclus ; ONCE, UP_TO_N envoyés")
    void sansCapsTargeting() {
        var upToN = campaign(1, VideoModalAudience.EVERYONE);
        var once = campaign(1, VideoModalAudience.EVERYONE);
        once.setFrequency(VideoModalFrequency.ONCE);
        var everyOpen = campaign(1, VideoModalAudience.EVERYONE);
        everyOpen.setFrequency(VideoModalFrequency.EVERY_OPEN);
        var everyDays = campaign(1, VideoModalAudience.EVERYONE);
        everyDays.setFrequency(VideoModalFrequency.EVERY_N_DAYS);
        everyDays.setFrequencyDays(3);
        var dontShow = campaign(1, VideoModalAudience.EVERYONE);
        dontShow.setAllowDontShowAgain(true);
        live(upToN, once, everyOpen, everyDays, dontShow);

        // Visiteur et caps absents ou « layout » seul : mêmes exclusions.
        assertThat(ids(service.eligible(null, "fr", null))).containsExactly(upToN.getId(), once.getId());
        assertThat(ids(service.eligible(null, "fr", "layout"))).containsExactly(upToN.getId(), once.getId());
        assertThat(ids(service.eligible(null, "fr", "layout,targeting"))).hasSize(5);
        assertThat(ids(service.eligible(null, "fr", " Targeting "))).hasSize(5);
    }

    @Test
    @DisplayName("sans caps=targeting, compte connecté : mêmes exclusions, les autres jugées côté serveur")
    void sansCapsTargetingConnecte() {
        knownUser();
        var upToN = campaign(1, VideoModalAudience.EVERYONE);
        var everyOpen = campaign(1, VideoModalAudience.EVERYONE);
        everyOpen.setFrequency(VideoModalFrequency.EVERY_OPEN);
        live(upToN, everyOpen);
        responses();

        assertThat(ids(service.eligible("uid", "fr", null))).containsExactly(upToN.getId());
        assertThat(ids(service.eligible("uid", "fr", "targeting"))).containsExactly(upToN.getId(), everyOpen.getId());
    }

    // ── Visiteurs ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("visiteur : jamais de campagne à conditions, situations, tirage < 100 ou connectedOnly ; sans condition, oui")
    void visiteurSansCompte() {
        var open = campaign(1, VideoModalAudience.EVERYONE);
        var conditioned = campaign(1, VideoModalAudience.EVERYONE);
        conditioned.setConditions(LANG_FR);
        var situated = campaign(1, VideoModalAudience.EVERYONE);
        situated.setSituations(List.of(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null)));
        var sampled = campaign(1, VideoModalAudience.EVERYONE);
        sampled.setSamplePercent(50);
        var connected = campaign(1, VideoModalAudience.EVERYONE);
        connected.setConnectedOnly(true);
        live(open, conditioned, situated, sampled, connected);

        var result = service.eligible(null, "fr", "layout,targeting");

        assertThat(ids(result)).containsExactly(open.getId());
        assertThat(result.get(0).displays()).isZero();
        assertThat(result.get(0).lastShownAt()).isNull();
        verifyNoInteractions(responseRepository, targeting);
    }

    @Test
    @DisplayName("compte supprimé ou inconnu : traité en visiteur, campagnes à conditions et situations exclues")
    void compteSupprime() {
        var open = campaign(1, VideoModalAudience.EVERYONE);
        var situated = campaign(1, VideoModalAudience.EVERYONE);
        situated.setSituations(List.of(new VideoModalSituation(VideoModalSituationKind.OFFER_UNANSWERED, 24)));
        live(open, situated);
        when(userRepository.findByFirebaseUid("deleted")).thenReturn(Optional.empty());

        assertThat(ids(service.eligible("deleted", "fr", "targeting"))).containsExactly(open.getId());
        verifyNoInteractions(responseRepository, targeting);
    }

    @Test
    @DisplayName("visiteur : version et système jugés aussi, priorité respectée")
    void visiteurVersionEtPriorite() {
        var low = campaign(1, VideoModalAudience.EVERYONE);
        var high = campaign(1, VideoModalAudience.EVERYONE);
        high.setPriority(10);
        var ios = campaign(1, VideoModalAudience.EVERYONE);
        ios.setPlatforms(List.of(VideoModalPlatform.IOS));
        var recent = campaign(1, VideoModalAudience.EVERYONE);
        recent.setMinAppVersion("1.0.6");
        live(low, high, ios, recent);

        assertThat(ids(service.eligible(null, "fr", "targeting", "1.0.5", "android"))).containsExactly(high.getId(), low.getId());
        assertThat(ids(service.eligible(null, "fr", "targeting", "1.0.6+1053", "ios")))
                .containsExactly(high.getId(), low.getId(), ios.getId(), recent.getId());
        // Version et système inconnus (app ancienne) : les campagnes restreintes ne sont pas envoyées.
        assertThat(ids(service.eligible(null, "fr", "targeting"))).containsExactly(high.getId(), low.getId());
        assertThat(ids(service.eligible(null, "fr", "targeting", "n'importe quoi", "web")))
                .containsExactly(high.getId(), low.getId());
    }

    // ── Aucun plafond global ───────────────────────────────────────────────

    @Test
    @DisplayName("aucun plafond global : un modal déjà montré aujourd'hui n'empêche pas les autres campagnes")
    void deuxModalsLeMemeJour() {
        knownUser();
        var a = campaign(1, VideoModalAudience.EVERYONE);
        var b = campaign(1, VideoModalAudience.EVERYONE);
        live(a, b);
        responses();

        assertThat(ids(service.eligible("uid", "fr", "targeting"))).containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    @DisplayName("aucune campagne active : rien n'est consulté")
    void aucuneCampagneRienConsulte() {
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of());
        assertThat(service.eligible("uid", "fr", "targeting")).isEmpty();
        verifyNoInteractions(userRepository, responseRepository, targeting);
    }

    // ── Tirage, conditions, situations ─────────────────────────────────────

    @Test
    @DisplayName("tirage stable : seuls les comptes tirés voient la campagne, toujours les mêmes")
    void tirage() {
        knownUser();
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setSamplePercent(50);
        live(c);
        responses();
        boolean inSample = VideoModalTargeting.inSample(userId, c.getId(), 50);

        for (int i = 0; i < 1000; i++) {
            assertThat(service.eligible("uid", "fr", "targeting").isEmpty()).isEqualTo(!inSample);
        }
    }

    @Test
    @DisplayName("conditions : le moteur de la Diffusion tranche, après les filtres peu coûteux")
    void conditions() {
        knownUser();
        var matching = campaign(1, VideoModalAudience.EVERYONE);
        matching.setConditions(LANG_FR);
        var rejected = campaign(1, VideoModalAudience.EVERYONE);
        rejected.setConditions("{\"children\":[{\"field\":\"country\",\"operator\":\"IN\",\"value\":[\"SN\"]}],\"op\":\"AND\"}");
        var alreadyDone = campaign(1, VideoModalAudience.EVERYONE);
        alreadyDone.setConditions(LANG_FR);
        live(matching, rejected, alreadyDone);
        var answered = shown(alreadyDone, NOW);
        answered.apply(VideoModalEvent.OPENED, NOW);
        responses(answered);
        when(targeting.matchesConditions(matching.getConditions(), userId)).thenReturn(true);
        when(targeting.matchesConditions(rejected.getConditions(), userId)).thenReturn(false);

        assertThat(ids(service.eligible("uid", "fr", "targeting"))).containsExactly(matching.getId());
        // La campagne déjà répondue n'a coûté aucune requête de ciblage.
        verify(targeting, times(2)).matchesConditions(any(), eq(userId));
    }

    @Test
    @DisplayName("situations : TOUTES doivent être vraies ; chaque situation n'est évaluée qu'une fois par appel")
    void situations() {
        knownUser();
        var unpaid = new VideoModalSituation(VideoModalSituationKind.OFFER_ACCEPTED_UNPAID, 24);
        var incomplete = new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null);
        var both = campaign(1, VideoModalAudience.EVERYONE);
        both.setSituations(List.of(unpaid, incomplete));
        var onlyUnpaid = campaign(1, VideoModalAudience.EVERYONE);
        onlyUnpaid.setSituations(List.of(unpaid));
        var falseOne = campaign(1, VideoModalAudience.EVERYONE);
        falseOne.setSituations(List.of(unpaid, new VideoModalSituation(VideoModalSituationKind.KYC_ABANDONED, null)));
        live(both, onlyUnpaid, falseOne);
        responses();
        when(targeting.holds(unpaid, userId)).thenReturn(true);
        when(targeting.holds(incomplete, userId)).thenReturn(true);
        when(targeting.holds(new VideoModalSituation(VideoModalSituationKind.KYC_ABANDONED, null), userId)).thenReturn(false);

        assertThat(ids(service.eligible("uid", "fr", "targeting"))).containsExactly(both.getId(), onlyUnpaid.getId());
        verify(targeting, times(1)).holds(unpaid, userId);
    }

    @Test
    @DisplayName("situation sans déclencheur : l'app reçoit triggers = [APP_OPEN]")
    void situationSansDeclencheur() {
        knownUser();
        var c = campaign(1, VideoModalAudience.EVERYONE);
        c.setTriggers(List.of());
        c.setSituations(List.of(new VideoModalSituation(VideoModalSituationKind.PROFILE_INCOMPLETE, null)));
        live(c);
        responses();
        when(targeting.holds(any(), eq(userId))).thenReturn(true);

        assertThat(service.eligible("uid", "fr", "targeting").get(0).triggers())
                .containsExactly(VideoModalTrigger.APP_OPEN);
    }

    // ── Réponse, « ne plus afficher », fréquence ───────────────────────────

    @Test
    @DisplayName("DONT_SHOW_AGAIN, plafond et fenêtre EVERY_N_DAYS jugés côté serveur ; lastShownAt exposé")
    void reponseEtFrequence() {
        knownUser();
        var dontShow = campaign(1, VideoModalAudience.EVERYONE);
        dontShow.setFrequency(VideoModalFrequency.EVERY_OPEN);
        var daily = campaign(1, VideoModalAudience.EVERYONE);
        daily.setFrequency(VideoModalFrequency.EVERY_N_DAYS);
        daily.setFrequencyDays(7);
        var dailyTooSoon = campaign(1, VideoModalAudience.EVERYONE);
        dailyTooSoon.setFrequency(VideoModalFrequency.EVERY_N_DAYS);
        dailyTooSoon.setFrequencyDays(7);
        var keepsGoing = campaign(1, VideoModalAudience.EVERYONE);
        keepsGoing.setStopAfterAnswer(false);
        live(dontShow, daily, dailyTooSoon, keepsGoing);

        var r1 = shown(dontShow, NOW.minusSeconds(100));
        r1.apply(VideoModalEvent.DONT_SHOW_AGAIN, NOW);
        Instant exactlyWeek = NOW.minus(Duration.ofDays(7));
        var r2 = shown(daily, exactlyWeek);
        var r3 = shown(dailyTooSoon, exactlyWeek.plusSeconds(1));
        var r4 = shown(keepsGoing, NOW.minusSeconds(3 * 86400L));
        r4.apply(VideoModalEvent.OPENED, NOW.minusSeconds(3 * 86400L));
        responses(r1, r2, r3, r4);

        List<VideoModalEligibleResponse> result = service.eligible("uid", "fr", "targeting");

        assertThat(ids(result)).containsExactly(daily.getId(), keepsGoing.getId());
        assertThat(result.get(0).lastShownAt()).isEqualTo(exactlyWeek);
        assertThat(result.get(0).frequency()).isEqualTo(VideoModalFrequency.EVERY_N_DAYS);
        assertThat(result.get(0).frequencyDays()).isEqualTo(7);
        assertThat(result.get(1).stopAfterAnswer()).isFalse();
        assertThat(result.get(1).lastShownAt()).isEqualTo(NOW.minusSeconds(3 * 86400L));
    }

    // ── Version, système et priorité côté compte ───────────────────────────

    @Test
    @DisplayName("tri : priorité décroissante, puis version publiée la plus récente (ordre du dépôt conservé)")
    void tri() {
        knownUser();
        var newest = campaign(1, VideoModalAudience.EVERYONE);
        var older = campaign(1, VideoModalAudience.EVERYONE);
        var urgentOld = campaign(1, VideoModalAudience.EVERYONE);
        urgentOld.setPriority(5);
        var urgentNewer = campaign(1, VideoModalAudience.EVERYONE);
        urgentNewer.setPriority(5);
        var negative = campaign(1, VideoModalAudience.EVERYONE);
        negative.setPriority(-3);
        live(urgentNewer, newest, urgentOld, older, negative);
        responses();

        assertThat(ids(service.eligible("uid", "fr", "targeting")))
                .containsExactly(urgentNewer.getId(), urgentOld.getId(), newest.getId(), older.getId(), negative.getId());
    }

    @Test
    @DisplayName("version et système : jugés pour un compte connecté, la version envoyée avec son build est lue")
    void versionCompte() {
        knownUser();
        var recent = campaign(1, VideoModalAudience.EVERYONE);
        recent.setMinAppVersion("1.0.6");
        var legacyOnly = campaign(1, VideoModalAudience.EVERYONE);
        legacyOnly.setMaxAppVersion("1.0.5");
        live(recent, legacyOnly);
        responses();

        assertThat(ids(service.eligible("uid", "fr", "targeting", "1.0.6+1053", "ios"))).containsExactly(recent.getId());
        assertThat(ids(service.eligible("uid", "fr", "targeting", "1.0.5", "android"))).containsExactly(legacyOnly.getId());
        assertThat(service.eligible("uid", "fr", "targeting")).isEmpty();
    }

    // ── Non-régression ─────────────────────────────────────────────────────

    @Test
    @DisplayName("campagne existante (défauts) : même réponse qu'avant, avec les nouveaux champs à leurs défauts")
    void nonRegression() {
        knownUser();
        var existing = campaign(2, VideoModalAudience.EVERYONE);
        live(existing);
        var r = shown(existing, NOW.minusSeconds(60));
        responses(r);

        var result = service.eligible("uid", "fr");

        assertThat(result).hasSize(1);
        var entry = result.get(0);
        assertThat(entry.displays()).isEqualTo(1);
        assertThat(entry.priority()).isZero();
        assertThat(entry.frequency()).isEqualTo(VideoModalFrequency.UP_TO_N);
        assertThat(entry.frequencyDays()).isNull();
        assertThat(entry.stopAfterAnswer()).isTrue();
        assertThat(entry.allowDontShowAgain()).isFalse();
        assertThat(entry.maxDisplays()).isEqualTo(3);
        assertThat(entry.triggers()).containsExactly(VideoModalTrigger.SEARCH_TAB);
        verifyNoInteractions(targeting);
    }

    @Test
    @DisplayName("infos : le type INFO porte aussi les réglages de fréquence")
    void infoPorteLesReglages() {
        var info = infoCampaign(1, VideoModalAudience.EVERYONE);
        info.setFrequency(VideoModalFrequency.EVERY_OPEN);
        info.setStopAfterAnswer(false);
        info.setAllowDontShowAgain(true);
        info.setPriority(2);
        live(info);

        var entry = service.eligible(null, "en", "targeting").get(0);

        assertThat(entry.type()).isEqualTo(VideoModalType.INFO);
        assertThat(entry.frequency()).isEqualTo(VideoModalFrequency.EVERY_OPEN);
        assertThat(entry.stopAfterAnswer()).isFalse();
        assertThat(entry.allowDontShowAgain()).isTrue();
        assertThat(entry.priority()).isEqualTo(2);
    }

    // ── Événements ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("événement DONT_SHOW_AGAIN : enregistré sur la version courante, sans toucher au compteur")
    void evenementDontShowAgain() {
        knownUser();
        var c = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(responseRepository.findByCampaignIdAndUserIdAndVersion(c.getId(), userId, 1)).thenReturn(Optional.empty());

        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.DONT_SHOW_AGAIN, null));

        ArgumentCaptor<VideoModalResponseEntity> saved = ArgumentCaptor.forClass(VideoModalResponseEntity.class);
        verify(responseRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isDontShowAgain()).isTrue();
        assertThat(saved.getValue().getDisplays()).isZero();
        assertThat(saved.getValue().getStatus()).isEqualTo(VideoModalEvent.SHOWN);
    }

    @Test
    @DisplayName("événement DONT_SHOW_AGAIN d'une ancienne version : ignoré sans erreur")
    void evenementAncienneVersion() {
        var c = campaign(3, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));

        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(2, VideoModalEvent.DONT_SHOW_AGAIN, null));

        verify(responseRepository, never()).saveAndFlush(any());
    }
}
