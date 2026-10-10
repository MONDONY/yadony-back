package com.yadony.api.matching;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import com.yadony.api.common.media.PhotoReconciliationService;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Photo de refus de colis : {@code bids.refusal_photo_url}. Valeur libre envoyée par le client
 * (aucun contrôle de préfixe, BidService.refuseParcel) : elle peut désigner une clé de n'importe
 * quel préfixe, la source les déclare donc tous. Bids supprimés logiquement compris.
 */
@Component
public class BidRefusalPhotoReferenceSource implements MediaKeyReferenceSource {

    private final BidRepository bids;

    public BidRefusalPhotoReferenceSource(BidRepository bids) {
        this.bids = bids;
    }

    @Override
    public Set<String> prefixes() {
        return new HashSet<>(PhotoReconciliationService.RECONCILED_PREFIXES);
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, bids.findRefusalPhotoValuesMatchingIncludingDeleted(prefix));
    }
}
