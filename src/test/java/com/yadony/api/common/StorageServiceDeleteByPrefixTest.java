package com.yadony.api.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageService.deleteByPrefix")
class StorageServiceDeleteByPrefixTest {

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
    @DisplayName("supprime tous les objets sous le préfixe")
    void deletesAllObjectsUnderPrefix() {
        S3Object obj1 = S3Object.builder().key("kyc/user1/photo1.jpg").build();
        S3Object obj2 = S3Object.builder().key("kyc/user1/photo2.jpg").build();
        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(List.of(obj1, obj2)).isTruncated(false).build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);
        doReturn(DeleteObjectsResponse.builder().build())
                .when(s3Client).deleteObjects(any(DeleteObjectsRequest.class));

        storageService.deleteByPrefix("kyc/user1/");

        ArgumentCaptor<DeleteObjectsRequest> captor =
                ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3Client).deleteObjects(captor.capture());
        List<String> keys = captor.getValue().delete().objects()
                .stream().map(ObjectIdentifier::key).toList();
        assertThat(keys).containsExactlyInAnyOrder("kyc/user1/photo1.jpg", "kyc/user1/photo2.jpg");
    }

    @Test
    @DisplayName("préfixe sans objets → deleteObjects jamais appelé")
    void emptyPrefix_doesNotCallDelete() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).isTruncated(false).build());

        storageService.deleteByPrefix("kyc/nobody/");

        verify(s3Client, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test
    @DisplayName("pagination S3 → deux appels listObjectsV2 si isTruncated")
    void paginatedPrefix_callsListTwice() {
        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                .contents(List.of(S3Object.builder().key("kyc/u/a.jpg").build()))
                .isTruncated(true).nextContinuationToken("tok1").build();
        ListObjectsV2Response page2 = ListObjectsV2Response.builder()
                .contents(List.of(S3Object.builder().key("kyc/u/b.jpg").build()))
                .isTruncated(false).build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page1, page2);
        doReturn(DeleteObjectsResponse.builder().build())
                .when(s3Client).deleteObjects(any(DeleteObjectsRequest.class));

        storageService.deleteByPrefix("kyc/u/");

        verify(s3Client, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
        verify(s3Client, times(2)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    // ------------------------------------------------------------ listObjectsOlderThan

    private static S3Object obj(String key, String lastModified) {
        return S3Object.builder().key(key).lastModified(java.time.Instant.parse(lastModified)).build();
    }

    @Test
    @DisplayName("listObjectsOlderThan : filtre par date et suit la pagination")
    void listObjectsOlderThan_filtersByDate_acrossPages() {
        java.time.Instant cutoff = java.time.Instant.parse("2026-10-09T04:10:00Z");
        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                .contents(List.of(obj("tracking/b/old1.jpg", "2026-10-01T00:00:00Z"),
                        obj("tracking/b/new.jpg", "2026-10-09T05:00:00Z")))
                .isTruncated(true).nextContinuationToken("tok1").build();
        ListObjectsV2Response page2 = ListObjectsV2Response.builder()
                .contents(List.of(obj("tracking/b/old2.jpg", "2026-10-09T04:09:59Z")))
                .isTruncated(false).build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page1, page2);

        List<String> keys = storageService.listObjectsOlderThan("tracking/", cutoff, 100);

        assertThat(keys).containsExactly("tracking/b/old1.jpg", "tracking/b/old2.jpg");
        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3Client, times(2)).listObjectsV2(captor.capture());
        assertThat(captor.getAllValues().get(0).prefix()).isEqualTo("tracking/");
        assertThat(captor.getAllValues().get(1).continuationToken()).isEqualTo("tok1");
    }

    @Test
    @DisplayName("listObjectsOlderThan : s'arrête à max sans lire la page suivante")
    void listObjectsOlderThan_stopsAtMax() {
        java.time.Instant cutoff = java.time.Instant.parse("2026-10-09T04:10:00Z");
        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                .contents(List.of(obj("bids/s/1.jpg", "2026-10-01T00:00:00Z"),
                        obj("bids/s/2.jpg", "2026-10-01T00:00:00Z"),
                        obj("bids/s/3.jpg", "2026-10-01T00:00:00Z")))
                .isTruncated(true).nextContinuationToken("tok1").build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page1);

        List<String> keys = storageService.listObjectsOlderThan("bids/", cutoff, 2);

        assertThat(keys).containsExactly("bids/s/1.jpg", "bids/s/2.jpg");
        verify(s3Client, times(1)).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    @DisplayName("listObjectsOlderThan : préfixe inconnu refusé")
    void listObjectsOlderThan_rejectsUnknownPrefix() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        storageService.listObjectsOlderThan("kyc/", java.time.Instant.now(), 10))
                .isInstanceOf(YadonyBusinessException.class);
        verifyNoInteractions(s3Client);
    }
}
