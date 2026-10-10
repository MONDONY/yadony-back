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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageServiceStoreImageTest {

    @Mock S3Client s3Client;
    @Mock S3Presigner s3Presigner;
    @Mock MediaUploadRepository uploads;
    @Mock PlatformSettingsService settings;

    StorageService storage;
    final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        storage = new StorageService(s3Client, s3Presigner, new ImageProcessingService(), uploads, settings);
        var f = StorageService.class.getDeclaredField("bucket");
        f.setAccessible(true);
        f.set(storage, "b");
        lenient().when(settings.photoUploadDailyQuota()).thenReturn(60);
        lenient().when(settings.trackingPhotosMaxPerBid()).thenReturn(6);
    }

    static MockMultipartFile jpeg(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return new MockMultipartFile("file", "p.jpg", "image/jpeg", out.toByteArray());
    }

    @Test
    void storesResizedJpeg_andRecordsUpload() throws Exception {
        String key = storage.storeImage(jpeg(4000, 3000),
                StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID));

        assertThat(key).startsWith("bids/" + owner + "/").endsWith(".jpg");
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().contentType()).isEqualTo("image/jpeg");
        ArgumentCaptor<MediaUploadEntity> row = ArgumentCaptor.forClass(MediaUploadEntity.class);
        verify(uploads).save(row.capture());
        assertThat(row.getValue().getObjectKey()).isEqualTo(key);
        assertThat(row.getValue().getPurpose()).isEqualTo(MediaPurpose.BID);
        assertThat(row.getValue().getSizeBytes()).isEqualTo(put.getValue().contentLength());
    }

    @Test
    void dailyQuotaReached_is429_andNothingStored() throws Exception {
        when(uploads.countByOwnerIdAndCreatedAtAfter(eq(owner), any())).thenReturn(60L);
        assertThatThrownBy(() -> storage.storeImage(jpeg(10, 10),
                StorageService.MediaUpload.of("reports/" + owner + "/", owner, MediaPurpose.REPORT)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(s3Client);
        verify(uploads, never()).save(any());
    }

    @Test
    void quotaExempt_skipsCount() throws Exception {
        storage.storeImage(jpeg(10, 10),
                new StorageService.MediaUpload("support/admin/" + owner + "/", owner, MediaPurpose.SUPPORT, null, true));
        verify(uploads, never()).countByOwnerIdAndCreatedAtAfter(any(), any());
    }

    @Test
    void trackingCapReached_is429() throws Exception {
        UUID bid = UUID.randomUUID();
        when(uploads.countByBidIdAndPurpose(bid, MediaPurpose.TRACKING)).thenReturn(6L);
        assertThatThrownBy(() -> storage.storeImage(jpeg(10, 10), StorageService.MediaUpload.tracking(bid, owner)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(s3Client);
    }

    @Test
    void over5Mb_is422() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> storage.storeImage(new MockMultipartFile("file", "p.jpg", "image/jpeg", big),
                StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void avatar_isCappedAt512() throws Exception {
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        storage.storeImage(jpeg(2000, 2000), StorageService.MediaUpload.of("users/uid/", owner, MediaPurpose.AVATAR));
        verify(s3Client).putObject(any(PutObjectRequest.class), body.capture());
        try (var in = body.getValue().contentStreamProvider().newStream()) {
            BufferedImage img = ImageIO.read(in);
            assertThat(img.getWidth()).isEqualTo(512);
        }
    }
}
