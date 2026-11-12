package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
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
import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoModalAdminServiceTest {

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

    static VideoModalRequest request(String url, Instant startsAt, Instant endsAt) {
        return new VideoModalRequest(" ", null, url, 204, " Bienvenue ", "Welcome", "Sous-titre", "",
                null, null, null, null, null, null, null, null, null,
                List.of(new VideoModalRequest.HighlightRequest(VideoModalIcon.LOCK, " Paiement protégé ", " ")),
                List.of(VideoModalTrigger.APP_OPEN, VideoModalTrigger.APP_OPEN, VideoModalTrigger.SEND_PARCEL),
                null, startsAt, endsAt, null, null, null, null, null, null);
    }

    private void savesItself() {
        when(campaignRepository.save(any())).thenAnswer(inv -> {
            VideoModalCampaignEntity c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    @Test
    @DisplayName("création : inactive, version 1, NEVER_ANSWERED, valeurs normalisées, audit")
    void creation() {
        savesItself();
        VideoModalAdminResponse r = service.create(request("https://youtu.be/dQw4w9WgXcQ", null, null), adminId);

        assertThat(r.active()).isFalse();
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.audience()).isEqualTo(VideoModalAudience.NEVER_ANSWERED);
        assertThat(r.versionPublishedAt()).isEqualTo(NOW);
        assertThat(r.youtubeVideoId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(r.name()).isEqualTo("Bienvenue");
        assertThat(r.titleFr()).isEqualTo("Bienvenue");
        assertThat(r.subtitleEn()).isNull();
        assertThat(r.maxDisplays()).isEqualTo(3);
        assertThat(r.triggers()).containsExactly(VideoModalTrigger.APP_OPEN, VideoModalTrigger.SEND_PARCEL);
        assertThat(r.highlights()).containsExactly(
                new VideoModalAdminResponse.Highlight(VideoModalIcon.LOCK, "Paiement protégé", null));
        assertThat(r.stats()).isEqualTo(VideoModalAdminResponse.Stats.EMPTY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(r.id()), eq("VIDEO_MODAL_CREATED"), eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("version", 1).containsEntry("active", false)
                .containsEntry("youtubeVideoId", "dQw4w9WgXcQ");
    }

    @Test
    @DisplayName("URL YouTube non reconnue : 422, rien d'enregistré")
    void urlInvalide() {
        assertThatThrownBy(() -> service.create(request("https://vimeo.com/1", null, null), adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("video-modal-invalid-youtube-url");
                });
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("lien http:// refusé à la création et à la nouvelle version (https seulement)")
    void httpRefuse() {
        assertThatThrownBy(() -> service.create(request("http://youtu.be/dQw4w9WgXcQ", null, null), adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("video-modal-invalid-youtube-url");
                    assertThat(e.getMessage()).contains("https");
                    assertThat(e.getDetail(com.yadony.api.common.i18n.TestMessages.en())).contains("https");
                });
        var c = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.publishVersion(c.getId(), new VideoModalVersionRequest(
                VideoModalAudience.EVERYONE, "http://www.youtube.com/watch?v=abcdefghijk"), adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("video-modal-invalid-youtube-url"));
        assertThat(c.getVersion()).isEqualTo(1);
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("un modal VIDÉO accepte EVERY_OPEN et EVERY_N_DAYS (création)")
    void frequencesPourUneVideo() {
        savesItself();
        var open = new VideoModalRequest(" ", VideoModalType.VIDEO, "https://youtu.be/dQw4w9WgXcQ", 204, "Titre",
                null, null, null, null, null, null, null, null, null, null, null, null, List.of(),
                List.of(VideoModalTrigger.APP_OPEN), null, null, null, null, null, null, null, null, null,
                VideoModalFrequency.EVERY_OPEN, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(service.create(open, adminId).frequency()).isEqualTo(VideoModalFrequency.EVERY_OPEN);

        var days = new VideoModalRequest(" ", VideoModalType.VIDEO, "https://youtu.be/dQw4w9WgXcQ", 204, "Titre",
                null, null, null, null, null, null, null, null, null, null, null, null, List.of(),
                List.of(VideoModalTrigger.APP_OPEN), null, null, null, null, null, null, null, null, null,
                VideoModalFrequency.EVERY_N_DAYS, 7, null, null, null, null, null, null, null, null, null, null);
        var r = service.create(days, adminId);
        assertThat(r.frequency()).isEqualTo(VideoModalFrequency.EVERY_N_DAYS);
        assertThat(r.frequencyDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("endsAt <= startsAt : 422")
    void periodeInvalide() {
        assertThatThrownBy(() -> service.create(request("https://youtu.be/dQw4w9WgXcQ", NOW, NOW), adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("video-modal-invalid-period"));
    }

    @Test
    @DisplayName("modification : contenus changés, version conservée, statistiques relues")
    void modification() {
        var c = campaign(2, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        savesItself();
        when(responseRepository.currentVersionStats(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{c.getId(), 5L, 3L, 1L, 2L}));

        var r = service.update(c.getId(), request("https://www.youtube.com/shorts/abcdefghijk", NOW, NOW.plusSeconds(60)), adminId);

        assertThat(r.version()).isEqualTo(2);
        assertThat(r.audience()).isEqualTo(VideoModalAudience.EVERYONE);
        assertThat(r.youtubeVideoId()).isEqualTo("abcdefghijk");
        assertThat(r.endsAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(r.stats()).isEqualTo(new VideoModalAdminResponse.Stats(5, 3, 1, 2));
        verify(auditService).log(eq("VIDEO_MODAL"), eq(c.getId()), eq("VIDEO_MODAL_UPDATED"), eq(adminId), any());
    }

    @Test
    @DisplayName("nouvelle version : version + 1, public choisi, publiée maintenant, vidéo facultative")
    void nouvelleVersion() {
        var c = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        savesItself();

        var r = service.publishVersion(c.getId(), new VideoModalVersionRequest(VideoModalAudience.EVERYONE, null), adminId);
        assertThat(r.version()).isEqualTo(2);
        assertThat(r.audience()).isEqualTo(VideoModalAudience.EVERYONE);
        assertThat(r.versionPublishedAt()).isEqualTo(NOW);
        assertThat(r.youtubeVideoId()).isEqualTo("dQw4w9WgXcQ");

        r = service.publishVersion(c.getId(), new VideoModalVersionRequest(VideoModalAudience.NOT_WATCHED_PREVIOUS,
                "https://www.youtube.com/watch?v=abcdefghijk"), adminId);
        assertThat(r.version()).isEqualTo(3);
        assertThat(r.youtubeVideoId()).isEqualTo("abcdefghijk");
        assertThat(r.youtubeUrl()).isEqualTo("https://www.youtube.com/watch?v=abcdefghijk");

        assertThatThrownBy(() -> service.publishVersion(c.getId(),
                new VideoModalVersionRequest(VideoModalAudience.EVERYONE, "pas une url"), adminId))
                .isInstanceOf(YadonyBusinessException.class);
        assertThat(c.getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("activation / désactivation : audit distinct")
    void activation() {
        var c = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        c.setActive(false);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        savesItself();
        when(responseRepository.currentVersionStats(anyCollection())).thenReturn(List.of());

        assertThat(service.setActive(c.getId(), true, adminId).active()).isTrue();
        assertThat(service.setActive(c.getId(), false, adminId).active()).isFalse();
        verify(auditService).log(eq("VIDEO_MODAL"), eq(c.getId()), eq("VIDEO_MODAL_ACTIVATED"), eq(adminId), any());
        verify(auditService).log(eq("VIDEO_MODAL"), eq(c.getId()), eq("VIDEO_MODAL_DEACTIVATED"), eq(adminId), any());
    }

    @Test
    @DisplayName("suppression douce + audit ; campagne inconnue : 404")
    void suppression() {
        var c = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        service.delete(c.getId(), adminId);
        assertThat(c.getDeletedAt()).isNotNull();
        verify(auditService).log(eq("VIDEO_MODAL"), eq(c.getId()), eq("VIDEO_MODAL_DELETED"), eq(adminId), any());

        UUID unknown = UUID.randomUUID();
        when(campaignRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(unknown))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("liste : statistiques par campagne, zéro sans réponse ; liste vide sans requête")
    void liste() {
        var a = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        var b = campaign(1, VideoModalAudience.NEVER_ANSWERED);
        when(campaignRepository.findAllNewestFirst()).thenReturn(List.of(a, b));
        when(responseRepository.currentVersionStats(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{a.getId(), 4L, 2L, null, 1}));

        var list = service.list();
        assertThat(list.get(0).stats()).isEqualTo(new VideoModalAdminResponse.Stats(4, 2, 0, 1));
        assertThat(list.get(1).stats()).isEqualTo(VideoModalAdminResponse.Stats.EMPTY);

        when(campaignRepository.findAllNewestFirst()).thenReturn(List.of());
        assertThat(service.list()).isEmpty();
    }
}
