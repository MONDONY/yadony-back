package com.yadony.api.signalements;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Photos de signalement : {@code report_photos.object_key} non supprimées. Seule
 * ReportPhotoPurgeParticipant supprime une ligne, APRÈS la suppression R2 ; un signalement
 * supprimé par l'admin (restaurable) garde ses lignes de photo vivantes.
 */
@Component
public class ReportPhotoReferenceSource implements MediaKeyReferenceSource {

    private final ReportPhotoRepository photos;

    public ReportPhotoReferenceSource(ReportPhotoRepository photos) {
        this.photos = photos;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("reports/");
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, photos.findLiveObjectKeyValuesMatching(prefix));
    }
}
