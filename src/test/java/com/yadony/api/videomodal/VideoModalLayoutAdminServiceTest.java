package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import com.yadony.api.videomodal.dto.VideoModalRequest;
import com.yadony.api.videomodal.dto.VideoModalRequest.ActionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static com.yadony.api.videomodal.VideoModalFixtures.infoCampaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Format, taille et image côté admin : validation 422, forçage NONE pour VIDEO, défauts. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Modals : format, taille et image (service admin)")
class VideoModalLayoutAdminServiceTest {

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalResponseRepository responseRepository;
    @Mock AuditService auditService;
    private final VideoModalImageUrls imageUrls = mock(VideoModalImageUrls.class);

    private VideoModalAdminService service;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VideoModalAdminService(campaignRepository, responseRepository, auditService,
                imageUrls, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void savesItself() {
        when(campaignRepository.save(any())).thenAnswer(inv -> {
            VideoModalCampaignEntity c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
    }

    private static VideoModalRequest info(VideoModalLayout layout, Integer size, VideoModalImageMode mode,
                                          VideoModalImageFit fit, String altFr, String altEn) {
        return new VideoModalRequest("Concours", VideoModalType.INFO, null, null, "Concours", null, null, null,
                "Participer", null, null, null, "Gagnez un lot.", null, VideoModalIcon.STAR,
                new ActionRequest("CLOSE", null, null), null, null, List.of(VideoModalTrigger.APP_OPEN), null,
                null, null, layout, size, mode, fit, altFr, altEn);
    }

    private static VideoModalRequest video(VideoModalLayout layout, Integer size, VideoModalImageMode mode) {
        return new VideoModalRequest("Vidéo", VideoModalType.VIDEO, "https://youtu.be/dQw4w9WgXcQ", 204, "Bienvenue",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(VideoModalTrigger.APP_OPEN), null, null, null, layout, size, mode, VideoModalImageFit.CONTAIN,
                "alt", "alt");
    }

    private void assertInvalid(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.getErrorCode()).isEqualTo(code);
        });
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("sans réglage : feuille du bas, taille automatique, sans image, cadrage COVER (rendu historique)")
    void defaults() {
        savesItself();
        VideoModalAdminResponse r = service.create(info(null, null, null, null, null, null), adminId);
        assertThat(r.layout()).isEqualTo(VideoModalLayout.BOTTOM_SHEET);
        assertThat(r.sizePercent()).isNull();
        assertThat(r.imageMode()).isEqualTo(VideoModalImageMode.NONE);
        assertThat(r.imageFit()).isEqualTo(VideoModalImageFit.COVER);
        assertThat(r.imageUrl()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 55, 100})
    @DisplayName("sizePercent 30 à 100 accepté")
    void sizeInRange(int size) {
        savesItself();
        assertThat(service.create(info(VideoModalLayout.CENTERED, size, null, null, null, null), adminId).sizePercent())
                .isEqualTo(size);
    }

    @ParameterizedTest
    @ValueSource(ints = {-5, 0, 29, 101, 1000})
    @DisplayName("sizePercent hors 30..100 : 422, pour INFO comme pour VIDEO")
    void sizeOutOfRange(int size) {
        assertInvalid(() -> service.create(info(VideoModalLayout.CENTERED, size, null, null, null, null), adminId),
                "video-modal-invalid-size");
        assertInvalid(() -> service.create(video(VideoModalLayout.BOTTOM_SHEET, size, null), adminId),
                "video-modal-invalid-size");
    }

    @Test
    @DisplayName("plein écran : la valeur de taille reste contrôlée mais sans effet")
    void fullscreen() {
        savesItself();
        VideoModalAdminResponse r = service.create(info(VideoModalLayout.FULLSCREEN, 50, null, null, null, null), adminId);
        assertThat(r.layout()).isEqualTo(VideoModalLayout.FULLSCREEN);
        assertThat(r.sizePercent()).isEqualTo(50);
    }

    @Test
    @DisplayName("imageMode TOP ou POSTER sans image envoyée : 422")
    void imageModeWithoutImage() {
        assertInvalid(() -> service.create(info(null, null, VideoModalImageMode.TOP, null, null, null), adminId),
                "video-modal-image-required");
        assertInvalid(() -> service.create(info(null, null, VideoModalImageMode.POSTER, null, null, null), adminId),
                "video-modal-image-required");
    }

    @Test
    @DisplayName("modification d'un INFO qui a déjà une image : mode, cadrage et textes alternatifs enregistrés")
    void imageModeWithImage() {
        VideoModalCampaignEntity c = infoCampaign(1, VideoModalAudience.EVERYONE);
        c.setImageKey("video-modals/x/1.png");
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(imageUrls.urlOf("video-modals/x/1.png")).thenReturn("https://r2/signed");

        VideoModalAdminResponse r = service.update(c.getId(),
                info(VideoModalLayout.FULLSCREEN, null, VideoModalImageMode.POSTER, VideoModalImageFit.CONTAIN,
                        "  Affiche du concours ", " "), adminId);

        assertThat(r.imageMode()).isEqualTo(VideoModalImageMode.POSTER);
        assertThat(r.imageFit()).isEqualTo(VideoModalImageFit.CONTAIN);
        assertThat(r.imageUrl()).isEqualTo("https://r2/signed");
        assertThat(r.imageAltFr()).isEqualTo("Affiche du concours");
        assertThat(r.imageAltEn()).isNull();
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(c.getId()), eq("VIDEO_MODAL_UPDATED"), eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("layout", "FULLSCREEN").containsEntry("imageMode", "POSTER");
    }

    @Test
    @DisplayName("VIDEO : imageMode forcé à NONE, textes alternatifs ignorés, format et taille gardés")
    void videoForcedNone() {
        savesItself();
        VideoModalAdminResponse r = service.create(
                video(VideoModalLayout.CENTERED, 60, VideoModalImageMode.POSTER), adminId);
        assertThat(r.type()).isEqualTo(VideoModalType.VIDEO);
        assertThat(r.imageMode()).isEqualTo(VideoModalImageMode.NONE);
        assertThat(r.imageAltFr()).isNull();
        assertThat(r.imageAltEn()).isNull();
        assertThat(r.layout()).isEqualTo(VideoModalLayout.CENTERED);
        assertThat(r.sizePercent()).isEqualTo(60);
    }

    @Test
    @DisplayName("VIDEO existante modifiée avec TOP : forcé NONE même si la ligne a une image")
    void videoUpdateIgnoresImage() {
        VideoModalCampaignEntity c = campaign(1, VideoModalAudience.EVERYONE);
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.update(c.getId(), video(null, null, VideoModalImageMode.TOP), adminId).imageMode())
                .isEqualTo(VideoModalImageMode.NONE);
    }
}
