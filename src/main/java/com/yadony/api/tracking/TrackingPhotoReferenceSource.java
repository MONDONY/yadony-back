package com.yadony.api.tracking;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Photos de scan : {@code tracking_events.photo_url} non purgée ({@code photo_purged_at IS NULL}). */
@Component
public class TrackingPhotoReferenceSource implements MediaKeyReferenceSource {

    private final TrackingEventRepository events;

    public TrackingPhotoReferenceSource(TrackingEventRepository events) {
        this.events = events;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("tracking/");
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, events.findLivePhotoValuesMatching(prefix));
    }
}
