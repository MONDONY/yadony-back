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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        lenient().when(settings.trackingPhotosMaxPerBid()).thenReturn(12);
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
        verify(uploads).saveAndFlush(row.capture());
        assertThat(row.getValue().getObjectKey()).isEqualTo(key);
        assertThat(row.getValue().getPurpose()).isEqualTo(MediaPurpose.BID);
        assertThat(row.getValue().getSizeBytes()).isEqualTo(put.getValue().contentLength());
    }

    @Test
    void insideActiveTransaction_stillStores_andWarns() throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            String key = storage.storeImage(jpeg(10, 10),
                    StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID));
            assertThat(key).startsWith("bids/" + owner + "/");
            verify(uploads).saveAndFlush(any(MediaUploadEntity.class));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void dailyQuotaReached_is429_andNothingStored() throws Exception {
        when(uploads.countByOwnerIdAndPurposeNotAndCreatedAtAfter(eq(owner), eq(MediaPurpose.TRACKING), any())).thenReturn(60L);
        assertThatThrownBy(() -> storage.storeImage(jpeg(10, 10),
                StorageService.MediaUpload.of("reports/" + owner + "/", owner, MediaPurpose.REPORT)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(s3Client);
        verify(uploads, never()).saveAndFlush(any());
    }

    @Test
    void quotaExempt_skipsCount() throws Exception {
        storage.storeImage(jpeg(10, 10),
                new StorageService.MediaUpload("support/admin/" + owner + "/", owner, MediaPurpose.SUPPORT, null, true));
        verify(uploads, never()).countByOwnerIdAndPurposeNotAndCreatedAtAfter(any(), any(), any());
        verify(uploads).saveAndFlush(any(MediaUploadEntity.class));
    }

    @Test
    void trackingCapReachedWithin24h_is429() throws Exception {
        UUID bid = UUID.randomUUID();
        ArgumentCaptor<java.time.LocalDateTime> since = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        when(uploads.countByBidIdAndPurposeAndCreatedAtAfter(eq(bid), eq(MediaPurpose.TRACKING), since.capture()))
                .thenReturn(12L);
        assertThatThrownBy(() -> storage.storeImage(jpeg(10, 10), StorageService.MediaUpload.tracking(bid, owner)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getErrorCode()).isEqualTo("tracking-photo-limit-reached");
                });
        verifyNoInteractions(s3Client);
        // Fenêtre glissante de 24 h, pas « depuis toujours ».
        java.time.LocalDateTime expected = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);
        assertThat(since.getValue()).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
    }

    @Test
    void trackingUploadsOlderThan24h_doNotBlockA13th() throws Exception {
        UUID bid = UUID.randomUUID();
        // 12 envois il y a 25 h : hors fenêtre, la requête sur 24 h n'en compte aucun.
        when(uploads.countByBidIdAndPurposeAndCreatedAtAfter(eq(bid), eq(MediaPurpose.TRACKING), any()))
                .thenReturn(0L);
        assertThat(storage.storeImage(jpeg(10, 10), StorageService.MediaUpload.tracking(bid, owner)))
                .startsWith("tracking/" + bid + "/");
    }

    @Test
    void trackingUpload_isExemptFromDailyQuota_evenAfter60RecentUploads() throws Exception {
        UUID bid = UUID.randomUUID();
        lenient().when(uploads.countByOwnerIdAndPurposeNotAndCreatedAtAfter(eq(owner), any(), any()))
                .thenReturn(60L);
        assertThat(storage.storeImage(jpeg(10, 10), StorageService.MediaUpload.tracking(bid, owner)))
                .startsWith("tracking/" + bid + "/");
        verify(uploads, never()).countByOwnerIdAndPurposeNotAndCreatedAtAfter(any(), any(), any());
        verify(uploads).saveAndFlush(any(MediaUploadEntity.class));
    }

    @Test
    void dailyQuota_excludesTrackingUploadsFromTheCount() throws Exception {
        storage.storeImage(jpeg(10, 10),
                StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID));
        verify(uploads).countByOwnerIdAndPurposeNotAndCreatedAtAfter(eq(owner), eq(MediaPurpose.TRACKING), any());
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

    @Test
    void putFails_reservationDeleted_andExceptionPropagates() throws Exception {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("r2 down"));
        assertThatThrownBy(() -> storage.storeImage(jpeg(10, 10),
                StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID)))
                .isInstanceOf(IllegalStateException.class).hasMessage("r2 down");
        ArgumentCaptor<MediaUploadEntity> reserved = ArgumentCaptor.forClass(MediaUploadEntity.class);
        verify(uploads).saveAndFlush(reserved.capture());
        verify(uploads).delete(reserved.getValue());
    }

    @Test
    void reservationFails_noPutObject_failClosed() throws Exception {
        when(uploads.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> storage.storeImage(jpeg(10, 10),
                StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(s3Client);
    }

    @Test
    void concurrentUploads_neverExceedQuota() throws Exception {
        when(settings.photoUploadDailyQuota()).thenReturn(5);
        java.util.concurrent.atomic.AtomicInteger rows = new java.util.concurrent.atomic.AtomicInteger();
        when(uploads.countByOwnerIdAndPurposeNotAndCreatedAtAfter(eq(owner), eq(MediaPurpose.TRACKING), any())).thenAnswer(i -> {
            long n = rows.get();
            Thread.sleep(5); // élargit la fenêtre de course entre compter et insérer
            return n;
        });
        when(uploads.saveAndFlush(any())).thenAnswer(i -> {
            rows.incrementAndGet();
            return i.getArgument(0);
        });
        MockMultipartFile photo = jpeg(10, 10);
        int n = 10;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    storage.storeImage(photo,
                            StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID));
                    return true;
                } catch (YadonyBusinessException e) {
                    return e.getStatus() == HttpStatus.TOO_MANY_REQUESTS ? false : null;
                }
            }));
        }
        start.countDown();
        int ok = 0;
        int tooMany = 0;
        for (var f : results) {
            Boolean r = f.get();
            if (Boolean.TRUE.equals(r)) ok++; else if (Boolean.FALSE.equals(r)) tooMany++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(5);
        assertThat(tooMany).isEqualTo(5);
    }

    @Test
    void unreadableFile_is422() throws Exception {
        MockMultipartFile real = jpeg(10, 10);
        org.springframework.web.multipart.MultipartFile file = spy(real);
        doThrow(new java.io.IOException("disque")).when(file).getBytes();
        assertThatThrownBy(() -> storage.storeImage(file,
                StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID)))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(s3Client);
    }

    @Test
    void sizeNotSaved_stillReturnsKey() throws Exception {
        when(uploads.save(any())).thenThrow(new IllegalStateException("db"));
        String key = storage.storeImage(jpeg(10, 10),
                StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID));
        assertThat(key).startsWith("bids/" + owner + "/");
    }

    @Test
    void releaseFails_originalExceptionStillPropagates() throws Exception {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("r2 down"));
        doThrow(new IllegalStateException("db down")).when(uploads).delete(any());
        assertThatThrownBy(() -> storage.storeImage(jpeg(10, 10),
                StorageService.MediaUpload.of("bids/" + owner + "/", owner, MediaPurpose.BID)))
                .isInstanceOf(IllegalStateException.class).hasMessage("r2 down");
    }

    @Test
    void trackingUploads_acrossManyBids_allSucceed() throws Exception {
        // Propriétaire et bid tombent sur des verrous différents pour au moins un des bids tirés.
        MockMultipartFile photo = jpeg(10, 10);
        for (int i = 0; i < 40; i++) {
            UUID bid = UUID.randomUUID();
            assertThat(storage.storeImage(photo, StorageService.MediaUpload.tracking(bid, owner)))
                    .startsWith("tracking/" + bid);
        }
        verify(uploads, times(40)).saveAndFlush(any(MediaUploadEntity.class));
    }
}
