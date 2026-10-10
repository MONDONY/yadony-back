package com.yadony.api.matching;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Photos de colis d'un bid : {@code bid_photos.object_key}, tous statuts. Une photo DELETING
 * reste revendiquée : BidPhotoCleanupScheduler la supprime de R2 puis efface la ligne.
 */
@Component
public class BidPhotoReferenceSource implements MediaKeyReferenceSource {

    private final BidPhotoRepository photos;

    public BidPhotoReferenceSource(BidPhotoRepository photos) {
        this.photos = photos;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("bids/");
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, photos.findObjectKeyValuesMatching(prefix));
    }
}
