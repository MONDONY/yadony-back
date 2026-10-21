package com.yadony.api.requests.service;

import com.yadony.api.common.StorageKeys;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.requests.dto.PackageRequestPhotoResponse;
import com.yadony.api.requests.entity.PackageRequestPhotoEntity;
import com.yadony.api.requests.repository.PackageRequestPhotoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cycle de vie des photos de colis d'une demande d'envoi : remplacement (création/édition),
 * lecture présignée, extraction des clés brutes (pour la copie vers le bid à la matérialisation).
 */
@Service
public class PackageRequestPhotoService {

    static final int MAX_PHOTOS = 4;
    static final String PHOTO_PREFIX = "package_requests/";
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final PackageRequestPhotoRepository photoRepository;
    private final StorageService storageService;

    public PackageRequestPhotoService(PackageRequestPhotoRepository photoRepository,
                                      StorageService storageService) {
        this.photoRepository = photoRepository;
        this.storageService = storageService;
    }

    /**
     * Remplace l'ensemble des photos d'une demande. Valide AVANT de supprimer (rollback propre).
     * Chaque clé doit appartenir au sender (prefix package_requests/{senderId}/). Max {@value #MAX_PHOTOS}.
     */
    @Transactional
    public void replacePhotos(UUID packageRequestId, UUID senderId, List<String> photoKeys) {
        List<PackageRequestPhotoEntity> rows = buildValidatedRows(packageRequestId, senderId, photoKeys);
        photoRepository.deleteByPackageRequestId(packageRequestId);
        if (!rows.isEmpty()) {
            photoRepository.saveAll(rows);
        }
    }

    private List<PackageRequestPhotoEntity> buildValidatedRows(UUID packageRequestId, UUID senderId,
                                                               List<String> photoKeys) {
        if (photoKeys == null || photoKeys.isEmpty()) {
            return List.of();
        }
        if (photoKeys.size() > MAX_PHOTOS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "too-many-photos", "Too Many Photos",
                    "Maximum " + MAX_PHOTOS + " photos par colis");
        }
        String ownerPrefix = PHOTO_PREFIX + senderId + "/";
        List<PackageRequestPhotoEntity> rows = new ArrayList<>();
        int position = 0;
        for (String key : photoKeys) {
            if (!StorageKeys.isOwnedKey(key, ownerPrefix)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-photo-key", "Invalid Photo Key",
                        "Clé photo invalide");
            }
            rows.add(new PackageRequestPhotoEntity(packageRequestId, key, position++));
        }
        return rows;
    }

    /** Clés brutes ordonnées (pour la copie vers le bid à la matérialisation). */
    @Transactional(readOnly = true)
    public List<String> objectKeys(UUID packageRequestId) {
        return photoRepository.findByPackageRequestIdOrderByPositionAsc(packageRequestId)
                .stream().map(PackageRequestPhotoEntity::getObjectKey).toList();
    }

    /** Photos en URLs présignées triées par position. */
    @Transactional(readOnly = true)
    public List<PackageRequestPhotoResponse> activePhotos(UUID packageRequestId) {
        return photoRepository.findByPackageRequestIdOrderByPositionAsc(packageRequestId)
                .stream()
                .map(p -> new PackageRequestPhotoResponse(p.getId(), p.getObjectKey(),
                        storageService.generatePresignedUrl(p.getObjectKey(), PRESIGN_TTL)))
                .toList();
    }

    /** URL présignée de la 1ère photo, ou null (alimente photo_url, rétro-compat). */
    @Transactional(readOnly = true)
    public String firstPhotoUrl(UUID packageRequestId) {
        return photoRepository.findFirstByPackageRequestIdOrderByPositionAsc(packageRequestId)
                .map(p -> storageService.generatePresignedUrl(p.getObjectKey(), PRESIGN_TTL))
                .orElse(null);
    }

    /**
     * Batch variant of {@link #activePhotos}: fetches photos for all given request IDs
     * in a single query and returns a map from packageRequestId to its list of photo responses.
     * IDs with no photos are absent from the map (callers may default to {@code List.of()}).
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<PackageRequestPhotoResponse>> activePhotosBatch(Collection<UUID> requestIds) {
        if (requestIds == null || requestIds.isEmpty()) return Map.of();
        List<PackageRequestPhotoEntity> all =
                photoRepository.findByPackageRequestIdInOrderByPositionAsc(new ArrayList<>(requestIds));
        Map<UUID, List<PackageRequestPhotoResponse>> result = new HashMap<>();
        for (PackageRequestPhotoEntity p : all) {
            result.computeIfAbsent(p.getPackageRequestId(), id -> new ArrayList<>())
                  .add(new PackageRequestPhotoResponse(
                          p.getId(), p.getObjectKey(),
                          storageService.generatePresignedUrl(p.getObjectKey(), PRESIGN_TTL)));
        }
        return result;
    }
}
