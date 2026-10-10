package com.yadony.api.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageService.copyObject")
class StorageServiceCopyObjectTest {

    @Mock private com.yadony.api.common.media.MediaUploadRepository uploads;
    @Mock private com.yadony.api.config.PlatformSettingsService settings;
    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;
    @Mock private ImageProcessingService imageProcessingService;

    private StorageService storageService;

    @BeforeEach
    void setUp() throws Exception {
        storageService = new StorageService(s3Client, s3Presigner, imageProcessingService, uploads, settings);
        var field = StorageService.class.getDeclaredField("bucket");
        field.setAccessible(true);
        field.set(storageService, "test-bucket");
    }

    @Test
    @DisplayName("copie vers destPrefix, préserve l'extension, renvoie la nouvelle clé")
    void copiesUnderDestPrefix() {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());

        String dest = storageService.copyObject("package_requests/sender/123_x.jpg", "bids/sender/");

        assertThat(dest).startsWith("bids/sender/").endsWith(".jpg");
        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(captor.capture());
        CopyObjectRequest req = captor.getValue();
        assertThat(req.sourceBucket()).isEqualTo("test-bucket");
        assertThat(req.sourceKey()).isEqualTo("package_requests/sender/123_x.jpg");
        assertThat(req.destinationBucket()).isEqualTo("test-bucket");
        assertThat(req.destinationKey()).isEqualTo(dest);
    }

    @Test
    @DisplayName("clé sans extension → défaut .jpg")
    void defaultsExtensionToJpg() {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        String dest = storageService.copyObject("package_requests/s/noext", "bids/s/");
        assertThat(dest).endsWith(".jpg");
    }

    @Test
    @DisplayName("échec S3 → propage l'exception")
    void propagatesFailure() {
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("boom").build());
        assertThatThrownBy(() -> storageService.copyObject("package_requests/s/1.jpg", "bids/s/"))
                .isInstanceOf(RuntimeException.class);
    }
}
