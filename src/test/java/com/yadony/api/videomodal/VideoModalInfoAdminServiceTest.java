package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import com.yadony.api.videomodal.dto.VideoModalRequest.ActionRequest;
import com.yadony.api.videomodal.dto.VideoModalVersionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.NOW;
import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Validation par type des campagnes (service d'administration) : INFO, actions, non-régression VIDEO. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals INFO : validation et enregistrement (admin)")
class VideoModalInfoAdminServiceTest {

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock AuditService auditService;

    private VideoModalAdminService service;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VideoModalAdminService(campaignRepository, responseRepository, auditService,
                mock(VideoModalImageUrls.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void savesItself() {
        when(campaignRepository.save(any())).thenAnswer(inv -> {
            VideoModalCampaignEntity c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    static ActionRequest close() { return new ActionRequest("CLOSE", null, null); }
    static ActionRequest screen(String s) { return new ActionRequest("SCREEN", s, null); }
    static ActionRequest url(String u) { return new ActionRequest("URL", null, u); }

    static VideoModalRequest info(String bodyFr, ActionRequest primary, String primaryLabelFr,
                                  ActionRequest secondary, String secondaryLabelFr) {
        return new VideoModalRequest("Info", VideoModalType.INFO, "https://youtu.be/ignore", 99,
                "Titre", "Title", "Sous-titre", null,
                primaryLabelFr, "Primary", secondaryLabelFr, "Secondary",
                bodyFr, "Body", VideoModalIcon.BELL, primary, secondary,
                List.of(new VideoModalRequest.HighlightRequest(VideoModalIcon.LOCK, "ignoré", null)),
                List.of(VideoModalTrigger.APP_OPEN), 2, null, null, null, null, null, null, null, null);
    }

    private void assertInvalid(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.getErrorCode()).isEqualTo(code);
        });
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("création INFO : actions normalisées, lien/durée/points forts ignorés, audit avec le type")
    void creationInfo() {
        savesItself();
        var r = service.create(info(" Votre colis est en route ", screen("TRACKING"), "Suivre mon colis",
                url(" https://yadony.com/aide "), "En savoir plus"), adminId);

        assertThat(r.type()).isEqualTo(VideoModalType.INFO);
        assertThat(r.bodyFr()).isEqualTo("Votre colis est en route");
        assertThat(r.bodyEn()).isEqualTo("Body");
        assertThat(r.icon()).isEqualTo(VideoModalIcon.BELL);
        assertThat(r.primaryAction()).isEqualTo(new VideoModalAction(VideoModalActionKind.SCREEN, InfoScreen.TRACKING, null));
        assertThat(r.secondaryAction()).isEqualTo(new VideoModalAction(VideoModalActionKind.URL, null, "https://yadony.com/aide"));
        assertThat(r.youtubeUrl()).isNull();
        assertThat(r.youtubeVideoId()).isNull();
        assertThat(r.durationSeconds()).isNull();
        assertThat(r.highlights()).isEmpty();
        assertThat(r.active()).isFalse();
        assertThat(r.version()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(r.id()), eq("VIDEO_MODAL_CREATED"), eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("type", "INFO").doesNotContainKey("youtubeVideoId");
    }

    @Test
    @DisplayName("icône par défaut INFO ; CLOSE sans libellé accepté ; secondaire absente : libellés secondaires vidés")
    void defauts() {
        savesItself();
        var req = new VideoModalRequest("Info", VideoModalType.INFO, null, null, "Titre", null, null, null,
                null, null, "reste", "reste", "Texte", null, null, close(), null,
                null, List.of(VideoModalTrigger.SEARCH_TAB), null, null, null, null, null, null, null, null, null);
        var r = service.create(req, adminId);
        assertThat(r.icon()).isEqualTo(VideoModalIcon.INFO);
        assertThat(r.primaryAction()).isEqualTo(new VideoModalAction(VideoModalActionKind.CLOSE, null, null));
        assertThat(r.secondaryAction()).isNull();
        assertThat(r.secondaryLabelFr()).isNull();
        assertThat(r.secondaryLabelEn()).isNull();
        assertThat(r.maxDisplays()).isEqualTo(3);
    }

    @Test
    @DisplayName("INFO sans action principale : 422")
    void sansActionPrincipale() {
        assertInvalid(() -> service.create(info("Texte", null, null, null, null), adminId),
                "video-modal-primary-action-required");
    }

    @Test
    @DisplayName("INFO sans texte FR : 422 (vide, espaces)")
    void sansTexte() {
        assertInvalid(() -> service.create(info(null, close(), null, null, null), adminId), "video-modal-body-required");
        assertInvalid(() -> service.create(info("   ", close(), null, null, null), adminId), "video-modal-body-required");
    }

    @Test
    @DisplayName("texte de 500 caractères accepté, 501 refusé (FR comme EN)")
    void longueurTexte() {
        savesItself();
        assertThat(service.create(info("a".repeat(500), close(), null, null, null), adminId).bodyFr()).hasSize(500);
        assertInvalidTwice(info("a".repeat(501), close(), null, null, null));
        var enTropLong = new VideoModalRequest("Info", VideoModalType.INFO, null, null, "Titre", null, null, null,
                null, null, null, null, "Texte", "b".repeat(501), null, close(), null,
                null, List.of(VideoModalTrigger.APP_OPEN), null, null, null, null, null, null, null, null, null);
        assertInvalidTwice(enTropLong);
    }

    private void assertInvalidTwice(VideoModalRequest r) {
        assertThatThrownBy(() -> service.create(r, adminId)).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("video-modal-body-too-long"));
    }

    @Test
    @DisplayName("action SCREEN : écran manquant ou hors liste : 422")
    void ecranInvalide() {
        assertInvalid(() -> service.create(info("T", screen(null), "Ouvrir", null, null), adminId), "video-modal-invalid-screen");
        assertInvalid(() -> service.create(info("T", screen(""), "Ouvrir", null, null), adminId), "video-modal-invalid-screen");
        assertInvalid(() -> service.create(info("T", screen("/admin"), "Ouvrir", null, null), adminId), "video-modal-invalid-screen");
        assertInvalid(() -> service.create(info("T", close(), null, screen("INCONNU"), "Ouvrir"), adminId), "video-modal-invalid-screen");
    }

    @Test
    @DisplayName("type d'action inconnu ou manquant : 422")
    void typeActionInvalide() {
        assertInvalid(() -> service.create(info("T", new ActionRequest(null, null, null), null, null, null), adminId),
                "video-modal-invalid-action");
        assertInvalid(() -> service.create(info("T", new ActionRequest("DELETE", null, null), null, null, null), adminId),
                "video-modal-invalid-action");
    }

    @Test
    @DisplayName("action URL : http, javascript:, data:, mailto:, vide, trop longue : 422")
    void urlInvalide() {
        for (String bad : new String[]{"http://yadony.com", "javascript:alert(1)", "data:text/html,x",
                "mailto:a@b.fr", "", "  ", "https://yadony.com/" + "a".repeat(2000)}) {
            assertInvalid(() -> service.create(info("T", url(bad), "Ouvrir", null, null), adminId), "video-modal-invalid-url");
        }
        assertInvalid(() -> service.create(info("T", url(null), "Ouvrir", null, null), adminId), "video-modal-invalid-url");
        assertInvalid(() -> service.create(info("T", close(), null, url("http://x.fr"), "Ouvrir"), adminId), "video-modal-invalid-url");
    }

    @Test
    @DisplayName("libellé FR obligatoire pour SCREEN et URL (principal et secondaire), 26 caractères au plus")
    void libelles() {
        assertInvalid(() -> service.create(info("T", screen("SEARCH"), " ", null, null), adminId), "video-modal-label-required");
        assertInvalid(() -> service.create(info("T", url("https://yadony.com"), null, null, null), adminId), "video-modal-label-required");
        assertInvalid(() -> service.create(info("T", close(), null, screen("SEARCH"), null), adminId), "video-modal-label-required");
        assertInvalid(() -> service.create(info("T", screen("SEARCH"), "x".repeat(27), null, null), adminId), "video-modal-label-too-long");
        assertInvalid(() -> service.create(info("T", close(), "OK", close(), "x".repeat(27)), adminId), "video-modal-label-too-long");
        savesItself();
        assertThat(service.create(info("T", screen("SEARCH"), "x".repeat(26), null, null), adminId).primaryLabelFr()).hasSize(26);
        // EN trop long
        var enLong = new VideoModalRequest("Info", VideoModalType.INFO, null, null, "Titre", null, null, null,
                "Ouvrir", "y".repeat(27), null, null, "Texte", null, null, screen("HELP"), null,
                null, List.of(VideoModalTrigger.APP_OPEN), null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(enLong, adminId)).isInstanceOfSatisfying(YadonyBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo("video-modal-label-too-long"));
    }

    @Test
    @DisplayName("tous les écrans de la liste fermée sont acceptés")
    void tousLesEcrans() {
        savesItself();
        for (InfoScreen s : InfoScreen.values()) {
            var r = service.create(info("T", screen(s.name()), "Ouvrir", null, null), adminId);
            assertThat(r.primaryAction().screen()).isEqualTo(s);
        }
    }

    @Test
    @DisplayName("VIDEO : sans type = VIDEO, lien YouTube toujours obligatoire (422), champs INFO effacés")
    void nonRegressionVideo() {
        savesItself();
        var video = new VideoModalRequest("V", null, "https://youtu.be/dQw4w9WgXcQ", 204, "Titre", null, null, null,
                null, null, null, null, "ignoré", "ignoré", VideoModalIcon.BELL, close(), close(),
                List.of(), List.of(VideoModalTrigger.APP_OPEN), null, null, null, null, null, null, null, null, null);
        var r = service.create(video, adminId);
        assertThat(r.type()).isEqualTo(VideoModalType.VIDEO);
        assertThat(r.youtubeVideoId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(r.bodyFr()).isNull();
        assertThat(r.icon()).isNull();
        assertThat(r.primaryAction()).isNull();
        assertThat(r.secondaryAction()).isNull();

        org.mockito.Mockito.clearInvocations(campaignRepository);
        for (String bad : new String[]{null, "", "  ", "https://vimeo.com/1"}) {
            var sansLien = new VideoModalRequest("V", VideoModalType.VIDEO, bad, null, "Titre", null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    List.of(), List.of(VideoModalTrigger.APP_OPEN), null, null, null, null, null, null, null, null, null);
            assertInvalid(() -> service.create(sansLien, adminId), "video-modal-invalid-youtube-url");
        }
    }

    @Test
    @DisplayName("modification d'un VIDEO en INFO (et inverse) : les champs de l'autre type sont remis à zéro")
    void changementDeType() {
        var c = campaign(2, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        savesItself();
        when(responseRepository.currentVersionStats(any())).thenReturn(List.of());

        var r = service.update(c.getId(), info("Texte", close(), null, null, null), adminId);
        assertThat(r.type()).isEqualTo(VideoModalType.INFO);
        assertThat(r.youtubeVideoId()).isNull();
        assertThat(r.version()).isEqualTo(2);
        assertThat(r.audience()).isEqualTo(VideoModalAudience.EVERYONE);
    }

    @Test
    @DisplayName("nouvelle version d'un INFO : version + 1, lien YouTube ignoré")
    void nouvelleVersionInfo() {
        savesItself();
        var created = service.create(info("Texte", close(), null, null, null), adminId);
        var c = campaignRepository.save(new VideoModalCampaignEntity());
        c.setType(VideoModalType.INFO);
        c.setTitleFr("T");
        c.setVersion(1);
        c.setAudience(VideoModalAudience.NEVER_ANSWERED);
        c.setVersionPublishedAt(NOW.minusSeconds(60));
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));

        var r = service.publishVersion(c.getId(), new VideoModalVersionRequest(VideoModalAudience.EVERYONE,
                "https://youtu.be/dQw4w9WgXcQ"), adminId);
        assertThat(r.version()).isEqualTo(2);
        assertThat(r.audience()).isEqualTo(VideoModalAudience.EVERYONE);
        assertThat(r.youtubeVideoId()).isNull();
        assertThat(created.type()).isEqualTo(VideoModalType.INFO);
    }
}
