package com.yadony.api.videomodal;

import com.yadony.api.common.media.MediaKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoModalImageReferenceSourceTest {

    private final VideoModalCampaignRepository repo = mock(VideoModalCampaignRepository.class);
    private final VideoModalImageReferenceSource source = new VideoModalImageReferenceSource(repo);

    @Test
    void declaresItsOwnPrefix_andIsKnownToStorage() {
        assertThat(source.prefixes()).containsExactly("video-modals/");
        assertThat(source.dedicated()).isTrue();
        assertThat(MediaKeys.KNOWN_PREFIXES).contains("video-modals/");
    }

    @Test
    void claimsTheKeysOfCampaigns() {
        when(repo.findImageKeysMatchingIncludingDeleted("video-modals/"))
                .thenReturn(List.of("video-modals/a/1.png", "https://r2.example/video-modals/b/2.jpg?X-Amz=1"));
        assertThat(source.referencedKeys("video-modals/"))
                .isEqualTo(Set.of("video-modals/a/1.png", "video-modals/b/2.jpg"));
    }
}
