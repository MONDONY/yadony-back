package com.yadony.api.requests.service;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import com.yadony.api.requests.repository.PackageRequestPhotoRepository;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Photos de demande : {@code package_request_photos.object_key} non purgées. Les photos d'une
 * demande supprimée logiquement restent revendiquées (la table n'a pas de deleted_at, seule
 * PackageRequestPhotoPurgeParticipant pose purged_at après suppression R2).
 */
@Component
public class PackageRequestPhotoReferenceSource implements MediaKeyReferenceSource {

    private final PackageRequestPhotoRepository photos;

    public PackageRequestPhotoReferenceSource(PackageRequestPhotoRepository photos) {
        this.photos = photos;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("package_requests/");
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, photos.findLiveObjectKeyValuesMatching(prefix));
    }
}
