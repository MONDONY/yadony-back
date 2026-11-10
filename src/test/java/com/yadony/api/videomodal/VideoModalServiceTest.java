package com.yadony.api.videomodal;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalEligibleResponse;
import com.yadony.api.videomodal.dto.VideoModalEventRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoModalServiceTest {

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock UserRepository userRepository;
    @Mock VideoModalImageUrls imageUrls;

    private VideoModalService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new VideoModalService(campaignRepository, responseRepository, userRepository, tx,
                imageUrls, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void knownUser(String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", userId);
        when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(u));
    }

    // ── GET eligible ──────────────────────────────────────────────────────

    @Test
    @DisplayName("aucune campagne active : liste vide, aucune lecture utilisateur")
    void aucuneCampagne() {
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of());
        assertThat(service.eligible("uid", "fr")).isEmpty();
        verifyNoInteractions(userRepository, responseRepository);
    }

    @Test
    @DisplayName("visiteur : toutes les campagnes dans leur période, ordre conservé, displays = 0")
    void visiteurToutesLesCampagnesDansLaPeriode() {
        var recent = campaign(2, VideoModalAudience.EVERYONE);
        var older = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        var notStarted = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        notStarted.setStartsAt(NOW.plusSeconds(60));
        var ended = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        ended.setEndsAt(NOW);
        var inPeriod = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        inPeriod.setStartsAt(NOW);
        inPeriod.setEndsAt(NOW.plusSeconds(1));
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc())
                .thenReturn(List.of(recent, notStarted, older, ended, inPeriod));

        List<VideoModalEligibleResponse> result = service.eligible(null, null);

        assertThat(result).extracting(VideoModalEligibleResponse::id)
                .containsExactly(recent.getId(), older.getId(), inPeriod.getId());
        assertThat(result).allSatisfy(r -> assertThat(r.displays()).isZero());
        verifyNoInteractions(responseRepository);
    }

    @Test
    @DisplayName("jeton sans ligne utilisateur : traité comme un visiteur")
    void uidInconnu() {
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc())
                .thenReturn(List.of(campaign(1, VideoModalAudience.NEVER_ANSWERED)));
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        assertThat(service.eligible("ghost", "fr")).hasSize(1);
        verifyNoInteractions(responseRepository);
    }

    @Test
    @DisplayName("utilisateur connu : exclusions appliquées, displays de la version courante")
    void utilisateurFiltre() {
        knownUser("uid");
        var answered = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        var capped = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        var shownOnce = campaign(2, VideoModalAudience.EVERYONE);
        var untouched = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc())
                .thenReturn(List.of(answered, capped, shownOnce, untouched));
        var r1 = new VideoModalResponseEntity(answered.getId(), userId, 1);
        r1.apply(VideoModalEvent.DISMISSED, NOW);
        var r2 = new VideoModalResponseEntity(capped.getId(), userId, 1);
        for (int i = 0; i < 3; i++) r2.apply(VideoModalEvent.SHOWN, NOW);
        var r3 = new VideoModalResponseEntity(shownOnce.getId(), userId, 2);
        r3.apply(VideoModalEvent.SHOWN, NOW);
        when(responseRepository.findByUserIdAndCampaignIdIn(any(), anyCollection())).thenReturn(List.of(r1, r2, r3));

        List<VideoModalEligibleResponse> result = service.eligible("uid", "fr");

        assertThat(result).extracting(VideoModalEligibleResponse::id).containsExactly(shownOnce.getId(), untouched.getId());
        assertThat(result.get(0).displays()).isEqualTo(1);
        assertThat(result.get(1).displays()).isZero();
    }

    @Test
    @DisplayName("textes dans la langue demandée, repli FR si EN vide, libellés par défaut")
    void textesEtReplis() {
        var c = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        c.setSubtitleEn("  ");
        when(campaignRepository.findActiveOrderByVersionPublishedAtDesc()).thenReturn(List.of(c));

        var en = service.eligible(null, "EN").get(0);
        assertThat(en.title()).isEqualTo("Welcome to Yadony");
        assertThat(en.subtitle()).isEqualTo("Sous-titre");
        assertThat(en.highlights()).extracting(VideoModalEligibleResponse.Highlight::text).containsExactly("Envoyer un colis");
        assertThat(en.primaryLabel()).isEqualTo("Watch the video (3 min)");
        assertThat(en.secondaryLabel()).isEqualTo("Get started");
        assertThat(en.youtubeVideoId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(en.maxDisplays()).isEqualTo(3);
        assertThat(en.audience()).isEqualTo(VideoModalAudience.NEVER_ANSWERED);
        assertThat(en.triggers()).containsExactly(VideoModalTrigger.SEARCH_TAB);

        var fr = service.eligible(null, "de").get(0);
        assertThat(fr.title()).isEqualTo("Bienvenue sur Yadony");
        assertThat(fr.primaryLabel()).isEqualTo("Voir la vidéo (3 min)");
        assertThat(fr.secondaryLabel()).isEqualTo("Commencer");

        c.setPrimaryLabelFr("Regarder");
        c.setSecondaryLabelFr("Plus tard");
        c.setSecondaryLabelEn("Later");
        assertThat(service.eligible(null, "en").get(0).primaryLabel()).isEqualTo("Regarder");
        assertThat(service.eligible(null, "en").get(0).secondaryLabel()).isEqualTo("Later");
    }

    @Test
    void libellePrincipalParDefaut() {
        assertThat(VideoModalTexts.defaultPrimaryLabel(false, null)).isEqualTo("Voir la vidéo");
        assertThat(VideoModalTexts.defaultPrimaryLabel(false, 20)).isEqualTo("Voir la vidéo (1 min)");
        assertThat(VideoModalTexts.defaultPrimaryLabel(true, 95)).isEqualTo("Watch the video (2 min)");
        assertThat(VideoModalTexts.pick(true, null, null)).isNull();
    }

    // ── POST events ───────────────────────────────────────────────────────

    @Test
    @DisplayName("campagne inconnue : 404 video-modal-not-found")
    void campagneInconnue() {
        UUID id = UUID.randomUUID();
        when(campaignRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordEvent("uid", id, new VideoModalEventRequest(1, VideoModalEvent.SHOWN, null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("video-modal-not-found");
                });
    }

    @Test
    @DisplayName("version ancienne ou inconnue : ignorée sans erreur")
    void versionIgnoree() {
        var c = campaign(2, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.OPENED, null));
        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(3, VideoModalEvent.OPENED, null));
        verifyNoInteractions(userRepository);
        verify(responseRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("compte sans ligne utilisateur : ignoré")
    void sansLigneUtilisateur() {
        var c = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(userRepository.findByFirebaseUid("ghost")).thenReturn(Optional.empty());
        service.recordEvent("ghost", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.SHOWN, null));
        service.recordEvent(null, c.getId(), new VideoModalEventRequest(1, VideoModalEvent.SHOWN, null));
        verify(responseRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("première réponse : ligne créée avec occurredAt passé (synchro hors compte)")
    void synchroOccurredAt() {
        knownUser("uid");
        var c = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(responseRepository.findByCampaignIdAndUserIdAndVersion(c.getId(), userId, 1)).thenReturn(Optional.empty());

        var past = NOW.minusSeconds(86_400);
        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.OPENED, past));

        var captor = ArgumentCaptor.forClass(VideoModalResponseEntity.class);
        verify(responseRepository).saveAndFlush(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getCampaignId()).isEqualTo(c.getId());
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(VideoModalEvent.OPENED);
        assertThat(saved.getAnsweredAt()).isEqualTo(past);
    }

    @Test
    @DisplayName("occurredAt dans le futur : ramené à maintenant ; ligne existante mise à jour")
    void occurredAtFutur() {
        knownUser("uid");
        var c = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        var existing = new VideoModalResponseEntity(c.getId(), userId, 1);
        existing.apply(VideoModalEvent.COMPLETED, NOW.minusSeconds(10));
        when(responseRepository.findByCampaignIdAndUserIdAndVersion(c.getId(), userId, 1)).thenReturn(Optional.of(existing));

        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.SHOWN, NOW.plusSeconds(3600)));
        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.DISMISSED, null));

        assertThat(existing.getLastShownAt()).isEqualTo(NOW);
        assertThat(existing.getDisplays()).isEqualTo(1);
        assertThat(existing.getStatus()).isEqualTo(VideoModalEvent.COMPLETED);
        verify(responseRepository, times(2)).saveAndFlush(existing);
    }

    @Test
    @DisplayName("course à la création (contrainte d'unicité) : une seconde tentative met à jour la ligne")
    void courseALaCreation() {
        knownUser("uid");
        var c = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        var existing = new VideoModalResponseEntity(c.getId(), userId, 1);
        when(responseRepository.findByCampaignIdAndUserIdAndVersion(c.getId(), userId, 1))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(responseRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq"))
                .thenReturn(existing);

        service.recordEvent("uid", c.getId(), new VideoModalEventRequest(1, VideoModalEvent.SHOWN, null));

        assertThat(existing.getDisplays()).isEqualTo(1);
        verify(responseRepository, times(2)).saveAndFlush(any());
    }
}
