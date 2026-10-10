package com.yadony.api.common;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.media.MediaPurpose;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/storage")
public class StorageController {

    private final StorageService storageService;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final UserRepository userRepository;

    public StorageController(StorageService storageService,
                             BidRepository bidRepository,
                             AnnouncementRepository announcementRepository,
                             UserRepository userRepository) {
        this.storageService = storageService;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.userRepository = userRepository;
    }

    /**
     * Upload a QR delivery photo.
     * Prefix built server-side: tracking/{bidId}/
     */
    @PostMapping("/upload/tracking")
    public ResponseEntity<Map<String, String>> uploadTrackingPhoto(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bidId") String bidId,
            @AuthenticationPrincipal String uid) {

        UUID bidUuid;
        try {
            bidUuid = UUID.fromString(bidId);
        } catch (IllegalArgumentException ex) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-bid-id", "Invalid Bid Id", "bidId doit être un UUID");
        }

        var bid = bidRepository.findById(bidUuid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.NOT_FOUND, "bid-not-found", "Not Found", "Bid introuvable"));
        var announcement = announcementRepository.findById(bid.getAnnouncementId())
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "announcement-not-found", "Not Found", "Annonce introuvable"));
        var user = userRepository.findByFirebaseUid(uid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthorized", "Utilisateur introuvable"));
        if (!announcement.getTravelerId().equals(user.getId())) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "not-bid-traveler", "Forbidden",
                    "Seul le voyageur de l'annonce peut uploader une photo de suivi");
        }

        String key = storageService.storeImage(file, StorageService.MediaUpload.tracking(bidUuid, user.getId()));
        String url = storageService.generatePresignedUrl(key, Duration.ofHours(1));

        return ResponseEntity.ok(Map.of("key", key, "url", url));
    }

    /**
     * Upload a package request photo (sender side).
     * Prefix built server-side: package_requests/{userId}/
     * Returns a presigned URL valid 7 days for embedding in the request.
     */
    @PostMapping("/upload/package-request")
    public ResponseEntity<Map<String, String>> uploadPackageRequestPhoto(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal String uid) {

        // Le prefix utilise user.getId() (UUID DB), jamais le raw uid → pas de risque
        // d'injection de chemin. On résout l'utilisateur comme le reste de l'app
        // (findByFirebaseUid) sans imposer UID_PATTERN, qui rejetait à tort certains
        // UID Firebase valides (ex. environnements dev / UID non strictement alphanum).
        if (uid == null) {
            throw new YadonyBusinessException(HttpStatus.UNAUTHORIZED,
                    "unauthenticated", "Unauthorized", "Non authentifié");
        }
        var user = userRepository.findByFirebaseUid(uid).orElseThrow(() -> new YadonyBusinessException(
                HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthorized", "Utilisateur introuvable"));

        String key = storageService.storeImage(file, StorageService.MediaUpload.of(
                "package_requests/" + user.getId() + "/", user.getId(), MediaPurpose.PACKAGE_REQUEST));
        // Public URL for marketplace display (signed 7 days, refresh on detail fetch).
        String url = storageService.generatePresignedUrl(key, Duration.ofDays(7));

        return ResponseEntity.ok(Map.of("key", key, "url", url));
    }
}
