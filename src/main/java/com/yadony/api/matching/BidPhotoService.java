package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.media.MediaPurpose;
import com.yadony.api.matching.dto.BidPhotoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Cycle de vie des photos de colis : upload, attache (ACTIVE), lecture présignée, passage DELETING. */
@Service
public class BidPhotoService {

    static final int MAX_PHOTOS = 4;
    static final String PHOTO_PREFIX = "bids/";
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final BidPhotoRepository photoRepository;
    private final StorageService storageService;

    public BidPhotoService(BidPhotoRepository photoRepository, StorageService storageService) {
        this.photoRepository = photoRepository;
        this.storageService = storageService;
    }

    /** Upload une photo sous le prefix bids/{senderId}/ ; renvoie la clé S3. */
    public String uploadPhoto(UUID senderId, MultipartFile file) {
        return storageService.storeImage(file, StorageService.MediaUpload.of(
                PHOTO_PREFIX + senderId + "/", senderId, MediaPurpose.BID));
    }

    /**
     * Persiste jusqu'à MAX_PHOTOS lignes ACTIVE pour un bid fraîchement créé.
     * Chaque clé doit vivre sous bids/{senderId}/ : accepter tout bids/* laisserait
     * un expéditeur rattacher (et donc lire en URL présignée) la photo d'un autre.
     */
    @Transactional
    public void attachPhotos(UUID bidId, UUID senderId, List<String> photoKeys) {
        if (photoKeys == null || photoKeys.isEmpty()) {
            return;
        }
        if (photoKeys.size() > MAX_PHOTOS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "too-many-photos", "Too Many Photos",
                    "Maximum " + MAX_PHOTOS + " photos par colis");
        }
        String ownerPrefix = PHOTO_PREFIX + senderId + "/";
        List<BidPhotoEntity> rows = new ArrayList<>();
        int position = 0;
        for (String key : photoKeys) {
            if (key == null || !key.startsWith(ownerPrefix)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "invalid-photo-key", "Invalid Photo Key",
                        "Clé photo invalide");
            }
            rows.add(new BidPhotoEntity(bidId, key, position++));
        }
        photoRepository.saveAll(rows);
    }

    /** Photos ACTIVE en URLs présignées, triées par position. */
    @Transactional(readOnly = true)
    public List<BidPhotoResponse> activePhotos(UUID bidId) {
        return photoRepository
                .findByBidIdAndStatusOrderByPositionAsc(bidId, BidPhotoStatus.ACTIVE)
                .stream()
                .map(p -> new BidPhotoResponse(p.getId(),
                        storageService.generatePresignedUrl(p.getObjectKey(), PRESIGN_TTL)))
                .toList();
    }

    /** Passe toutes les photos ACTIVE d'un bid en DELETING (idempotent). */
    @Transactional
    public void markDeletingForBid(UUID bidId) {
        List<BidPhotoEntity> active =
                photoRepository.findByBidIdAndStatusOrderByPositionAsc(bidId, BidPhotoStatus.ACTIVE);
        for (BidPhotoEntity p : active) {
            p.markDeleting();
        }
        photoRepository.saveAll(active);
    }
}
