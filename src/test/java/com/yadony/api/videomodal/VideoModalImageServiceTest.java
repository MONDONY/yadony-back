package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.media.MediaPurpose;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalFixtures.campaign;
import static com.yadony.api.videomodal.VideoModalFixtures.infoCampaign;
import static com.yadony.api.videomodal.VideoModalImageTestFiles.image;
import static com.yadony.api.videomodal.VideoModalImageTestFiles.jpegHeaderOnly;
import static com.yadony.api.videomodal.VideoModalImageTestFiles.webp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Image d'un modal : envoi, remplacement, retrait (service)")
class VideoModalImageServiceTest {

    @Mock VideoModalCampaignRepository campaignRepository;
    @Mock VideoModalAdminService adminService;
    @Mock StorageService storageService;
    @Mock AuditService auditService;

    private VideoModalImageService service;
    private VideoModalCampaignEntity info;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VideoModalImageService(campaignRepository, adminService, storageService, auditService);
        info = infoCampaign(1, VideoModalAudience.EVERYONE);
    }

    private void found(VideoModalCampaignEntity c) {
        when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
    }

    private void assertInvalid(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.getErrorCode()).isEqualTo(code);
        });
        verify(storageService, never()).storeAsset(any(), anyString(), anyString(), any());
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("PNG valide : stocké tel quel sous video-modals/{id}/, clé enregistrée, audit")
    void uploadPng() throws Exception {
        found(info);
        when(storageService.storeAsset(any(), eq("image/png"), eq("png"), any())).thenReturn("video-modals/k/1.png");
        VideoModalAdminResponse expected = org.mockito.Mockito.mock(VideoModalAdminResponse.class);
        when(adminService.get(info.getId())).thenReturn(expected);
        byte[] png = image("png", 800, 1200);

        assertThat(service.upload(info.getId(), new MockMultipartFile("file", "a.png", "image/png", png), adminId))
                .isSameAs(expected);

        ArgumentCaptor<StorageService.MediaUpload> up = ArgumentCaptor.forClass(StorageService.MediaUpload.class);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storageService).storeAsset(bytes.capture(), eq("image/png"), eq("png"), up.capture());
        assertThat(bytes.getValue()).isEqualTo(png);
        assertThat(up.getValue().prefix()).isEqualTo("video-modals/" + info.getId() + "/");
        assertThat(up.getValue().ownerId()).isEqualTo(adminId);
        assertThat(up.getValue().purpose()).isEqualTo(MediaPurpose.VIDEO_MODAL);
        assertThat(up.getValue().quotaExempt()).isTrue();
        assertThat(info.getImageKey()).isEqualTo("video-modals/k/1.png");
        verify(storageService, never()).deleteQuietly(anyString());
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(info.getId()), eq("VIDEO_MODAL_IMAGE_UPLOADED"), eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("imageKey", "video-modals/k/1.png")
                .containsEntry("imageType", "image/png").containsEntry("sizeBytes", png.length);
    }

    @Test
    @DisplayName("JPEG « image/jpg » normalisé, ancien fichier supprimé au remplacement, propriétaire = campagne sans admin")
    void replaceJpeg() throws Exception {
        info.setImageKey("video-modals/old/0.png");
        found(info);
        when(storageService.storeAsset(any(), eq("image/jpeg"), eq("jpg"), any())).thenReturn("video-modals/k/2.jpg");

        service.upload(info.getId(), new MockMultipartFile("file", "a.jpg", "image/jpg", image("jpg", 2400, 2400)), null);

        verify(storageService).deleteQuietly("video-modals/old/0.png");
        ArgumentCaptor<StorageService.MediaUpload> up = ArgumentCaptor.forClass(StorageService.MediaUpload.class);
        verify(storageService).storeAsset(any(), anyString(), anyString(), up.capture());
        assertThat(up.getValue().ownerId()).isEqualTo(info.getId());
    }

    @Test
    @DisplayName("WebP accepté")
    void uploadWebp() {
        found(info);
        when(storageService.storeAsset(any(), eq("image/webp"), eq("webp"), any())).thenReturn("video-modals/k/3.webp");
        service.upload(info.getId(), new MockMultipartFile("file", "a.webp", "image/webp", webp()), adminId);
        assertThat(info.getImageKey()).isEqualTo("video-modals/k/3.webp");
    }

    @Test
    @DisplayName("campagne inconnue : 404 ; VIDEO : 422")
    void unknownAndVideo() {
        UUID id = UUID.randomUUID();
        when(campaignRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upload(id, new MockMultipartFile("file", new byte[]{1}), adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.remove(id, adminId)).isInstanceOf(YadonyBusinessException.class);

        VideoModalCampaignEntity video = campaign(1, VideoModalAudience.EVERYONE);
        found(video);
        assertInvalid(() -> service.upload(video.getId(), new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}), adminId),
                "video-modal-image-info-only");
    }

    @Test
    @DisplayName("plus de 3 Mo : 422")
    void tooHeavy() {
        found(info);
        assertInvalid(() -> service.upload(info.getId(), new MockMultipartFile("file", "a.jpg", "image/jpeg",
                jpegHeaderOnly(3 * 1024 * 1024 + 1)), adminId), "video-modal-image-too-heavy");
    }

    @Test
    @DisplayName("exactement 3 Mo mais octets illisibles : pas de dépassement de poids, refus comme image invalide")
    void exactlyThreeMegabytes() {
        found(info);
        assertInvalid(() -> service.upload(info.getId(), new MockMultipartFile("file", "a.jpg", "image/jpeg",
                jpegHeaderOnly(3 * 1024 * 1024)), adminId), "video-modal-image-invalid");
    }

    @Test
    @DisplayName("plus de 2400 px de côté : 422")
    void tooLarge() throws Exception {
        found(info);
        byte[] wide = image("png", 2401, 10);
        byte[] tall = image("png", 10, 2401);
        assertInvalid(() -> service.upload(info.getId(), new MockMultipartFile("file", "a.png", "image/png", wide), adminId),
                "video-modal-image-too-large");
        assertInvalid(() -> service.upload(info.getId(), new MockMultipartFile("file", "a.png", "image/png", tall), adminId),
                "video-modal-image-too-large");
    }

    @Test
    @DisplayName("signature valide mais contenu non décodable : 422")
    void notAnImage() {
        found(info);
        assertInvalid(() -> service.upload(info.getId(),
                new MockMultipartFile("file", "a.jpg", "image/jpeg", jpegHeaderOnly(64)), adminId), "video-modal-image-invalid");
    }

    @Test
    @DisplayName("la validation de type du stockage s'applique avant tout (type annoncé faux)")
    void storageValidationFirst() {
        found(info);
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});
        doThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FILE_TYPE", "Invalid File Type", "x"))
                .when(storageService).validateImageUpload(file);
        assertThatThrownBy(() -> service.upload(info.getId(), file, adminId))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_FILE_TYPE"));
        verify(storageService, never()).storeAsset(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("échec d'enregistrement après l'envoi : le nouveau fichier est supprimé, l'ancien conservé")
    void saveFailureCleansNewFile() throws Exception {
        info.setImageKey("video-modals/old/0.png");
        found(info);
        when(storageService.storeAsset(any(), anyString(), anyString(), any())).thenReturn("video-modals/new/1.png");
        when(campaignRepository.save(any())).thenThrow(new IllegalStateException("base indisponible"));

        assertThatThrownBy(() -> service.upload(info.getId(),
                new MockMultipartFile("file", "a.png", "image/png", image("png", 10, 10)), adminId))
                .isInstanceOf(IllegalStateException.class);

        verify(storageService).deleteQuietly("video-modals/new/1.png");
        verify(storageService, never()).deleteQuietly("video-modals/old/0.png");
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("retrait : clé effacée, mode d'image repassé à NONE, fichier supprimé, audit")
    void remove() {
        info.setImageKey("video-modals/k/1.png");
        info.setImageMode(VideoModalImageMode.POSTER);
        found(info);

        service.remove(info.getId(), adminId);

        assertThat(info.getImageKey()).isNull();
        assertThat(info.getImageMode()).isEqualTo(VideoModalImageMode.NONE);
        verify(campaignRepository).save(info);
        verify(storageService).deleteQuietly("video-modals/k/1.png");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(info.getId()), eq("VIDEO_MODAL_IMAGE_REMOVED"), eq(adminId), payload.capture());
        assertThat(payload.getValue()).containsEntry("previousImageMode", "POSTER");
    }

    @Test
    @DisplayName("retrait sans image : sans effet, sans audit")
    void removeWithoutImage() {
        found(info);
        service.remove(info.getId(), adminId);
        verify(campaignRepository, never()).save(any());
        verify(storageService, never()).deleteQuietly(anyString());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), any());
    }
}
