package com.yadony.api.requests.service;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import com.yadony.api.common.media.PhotoReconciliationService;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Ancienne photo unique d'une demande : {@code package_requests.photo_url}. Valeur libre du
 * client (seuls http(s) et « .. » sont refusés) : elle peut désigner une clé de n'importe quel
 * préfixe, la source les déclare donc tous. Demandes supprimées logiquement comprises.
 */
@Component
public class PackageRequestLegacyPhotoReferenceSource implements MediaKeyReferenceSource {

    private final PackageRequestRepository requests;

    public PackageRequestLegacyPhotoReferenceSource(PackageRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    public Set<String> prefixes() {
        return new HashSet<>(PhotoReconciliationService.RECONCILED_PREFIXES);
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, requests.findPhotoUrlValuesMatchingIncludingDeleted(prefix));
    }
}
