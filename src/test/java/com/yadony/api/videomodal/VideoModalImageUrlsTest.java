package com.yadony.api.videomodal;

import com.yadony.api.common.StorageService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VideoModalImageUrlsTest {

    private final StorageService storage = mock(StorageService.class);
    private final VideoModalImageUrls urls = new VideoModalImageUrls(storage);

    @Test
    void presignsTheKeyForSevenDays() {
        when(storage.generatePresignedUrl("video-modals/x/1.png", Duration.ofDays(7))).thenReturn("https://r2/x");
        assertThat(urls.urlOf("video-modals/x/1.png")).isEqualTo("https://r2/x");
        verify(storage).generatePresignedUrl("video-modals/x/1.png", VideoModalImageUrls.TTL);
    }

    @Test
    void noKey_noUrl() {
        assertThat(urls.urlOf(null)).isNull();
        assertThat(urls.urlOf("  ")).isNull();
        verifyNoInteractions(storage);
    }
}
