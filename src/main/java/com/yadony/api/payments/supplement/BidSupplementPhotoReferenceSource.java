package com.yadony.api.payments.supplement;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Photo d'un supplément de prix : {@code bid_supplements.photo_key}, toujours sous
 * {@code tracking/<bidId>/} (contrôlé à la création). Suppléments supprimés logiquement compris :
 * la photo reste la preuve de l'écart de prix tant que la demande existe en base.
 */
@Component
public class BidSupplementPhotoReferenceSource implements MediaKeyReferenceSource {

    private final BidSupplementRepository supplements;

    public BidSupplementPhotoReferenceSource(BidSupplementRepository supplements) {
        this.supplements = supplements;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("tracking/");
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, supplements.findPhotoKeysUnderIncludingDeleted(prefix));
    }
}
