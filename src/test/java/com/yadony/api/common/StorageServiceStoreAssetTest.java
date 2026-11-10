package com.yadony.api.common;

import com.yadony.api.common.media.MediaPurpose;
import com.yadony.api.common.media.MediaUploadEntity;
import com.yadony.api.common.media.MediaUploadRepository;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code storeAsset} : octets gardés tels quels, clé horodatée, registre hors quota, nettoyage en cas d'échec. */
@ExtendWith(MockitoExtension.class)
class StorageServiceStoreAssetTest {

    @Mock S3Client s3Client;
    @Mock S3Presigner s3Presigner;
    @Mock MediaUploadRepository uploads;
    @Mock PlatformSettingsService settings;

    StorageService storage;
    final UUID owner = UUID.randomUUID();
    final UUID campaign = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        storage = new StorageService(s3Client, s3Presigner, new ImageProcessingService(), uploads, settings);
        var f = StorageService.class.getDeclaredField("bucket");
        f.setAccessible(true);
        f.set(storage, "b");
    }

    private StorageService.MediaUpload upload() {
        return new StorageService.MediaUpload("video-modals/" + campaign + "/", owner, MediaPurpose.VIDEO_MODAL, null, true);
    }

    @Test
    void storesOriginalBytes_underTimestampedKey_withoutQuota() {
        byte[] bytes = {1, 2, 3, 4};
        String key = storage.storeAsset(bytes, "image/png", "png", upload());

        assertThat(key).matches("video-modals/" + campaign + "/\\d+\\.png");
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).isEqualTo(key);
        assertThat(put.getValue().contentType()).isEqualTo("image/png");
        assertThat(put.getValue().contentLength()).isEqualTo(4L);
        verify(uploads, never()).countByOwnerIdAndPurposeNotAndCreatedAtAfter(any(), any(), any());
        ArgumentCaptor<MediaUploadEntity> row = ArgumentCaptor.forClass(MediaUploadEntity.class);
        verify(uploads).saveAndFlush(row.capture());
        assertThat(row.getValue().getObjectKey()).isEqualTo(key);
        assertThat(row.getValue().getPurpose()).isEqualTo(MediaPurpose.VIDEO_MODAL);
    }

    @Test
    void unknownPrefix_isRefused_andNothingStored() {
        assertThatThrownBy(() -> storage.storeAsset(new byte[]{1}, "image/png", "png",
                new StorageService.MediaUpload("inconnu/x/", owner, MediaPurpose.VIDEO_MODAL, null, true)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("INVALID_PREFIX"));
        verifyNoInteractions(s3Client);
    }

    @Test
    void writeFailure_releasesTheReservation() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("R2 indisponible"));
        assertThatThrownBy(() -> storage.storeAsset(new byte[]{1}, "image/jpeg", "jpg", upload()))
                .isInstanceOf(IllegalStateException.class);
        verify(uploads).delete(any(MediaUploadEntity.class));
    }
}
