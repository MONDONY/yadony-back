package com.yadony.api.videomodal;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Images des modals : {@code video_modal_campaigns.image_key}, campagnes supprimées comprises
 * (suppression douce : l'objet reste revendiqué tant que la ligne existe).
 */
@Component
public class VideoModalImageReferenceSource implements MediaKeyReferenceSource {

    static final String PREFIX = "video-modals/";

    private final VideoModalCampaignRepository campaigns;

    public VideoModalImageReferenceSource(VideoModalCampaignRepository campaigns) {
        this.campaigns = campaigns;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of(PREFIX);
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, campaigns.findImageKeysMatchingIncludingDeleted(prefix));
    }
}
