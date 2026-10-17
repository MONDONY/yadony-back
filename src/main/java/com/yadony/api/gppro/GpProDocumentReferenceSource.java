package com.yadony.api.gppro;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Justificatifs GP Pro : {@code gp_pro_documents.storage_key}, y compris non rattachés. */
@Component
public class GpProDocumentReferenceSource implements MediaKeyReferenceSource {

    private final GpProDocumentRepository documents;

    public GpProDocumentReferenceSource(GpProDocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("gp_pro/");
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, documents.findStorageKeysMatchingIncludingDeleted(prefix));
    }
}
