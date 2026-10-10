package com.yadony.api.common;

import com.yadony.api.common.media.MediaKeys;
import com.yadony.api.common.media.MediaPurpose;
import com.yadony.api.common.media.MediaUploadEntity;
import com.yadony.api.common.media.MediaUploadRepository;
import com.yadony.api.config.PlatformSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class StorageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageService.class);

    /** Source de vérité : MediaKeys.KNOWN_PREFIXES (le rapprochement reconnaît ainsi chaque préfixe). */
    private static final Set<String> ALLOWED_PREFIXES = Set.copyOf(MediaKeys.KNOWN_PREFIXES);
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024; // 5 Mo : l'app compresse avant envoi
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp");

    // Number of bytes needed to check the most demanding magic signature (WebP = 12)
    private static final int MAGIC_HEADER_SIZE = 12;

    // Magic byte signatures for supported image types (checked before MIME header)
    private static final Map<String, byte[]> FILE_MAGIC_BYTES = Map.of(
            "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "image/jpg",  new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "image/png",  new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}
            // WebP validated separately: bytes 0-3 = "RIFF", bytes 8-11 = "WEBP"
    );

    // Verrous par hash (bornés, pas de fuite mémoire) : voir reserve().
    private static final Object[] QUOTA_LOCKS = newLocks(64);

    private static Object[] newLocks(int n) {
        Object[] locks = new Object[n];
        for (int i = 0; i < n; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final ImageProcessingService imageProcessingService;
    private final MediaUploadRepository mediaUploadRepository;
    private final PlatformSettingsService settings;

    @Value("${aws.s3.bucket}")
    private String bucket;

    public StorageService(S3Client s3Client, S3Presigner s3Presigner,
                          ImageProcessingService imageProcessingService,
                          MediaUploadRepository mediaUploadRepository,
                          PlatformSettingsService settings) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.imageProcessingService = imageProcessingService;
        this.mediaUploadRepository = mediaUploadRepository;
        this.settings = settings;
    }

    /**
     * Client-facing URL for a stored avatar reference. null/blank → null.
     * Already-absolute http(s) values are returned as-is (legacy).
     * Otherwise the value is treated as an object key and a presigned GET URL
     * (7-day TTL) is returned.
     */
    public String avatarUrl(String storedKeyOrUrl) {
        if (storedKeyOrUrl == null || storedKeyOrUrl.isBlank()) return null;
        if (storedKeyOrUrl.startsWith("http://") || storedKeyOrUrl.startsWith("https://")) return storedKeyOrUrl;
        return generatePresignedUrl(storedKeyOrUrl, java.time.Duration.ofDays(7));
    }

    /**
     * Destination et propriétaire d'un envoi. {@code bidId} n'est utile qu'au tracking
     * (plafond par bid) ; {@code quotaExempt} est réservé aux envois d'un admin.
     */
    public record MediaUpload(String prefix, UUID ownerId, MediaPurpose purpose, UUID bidId, boolean quotaExempt) {
        public static MediaUpload of(String prefix, UUID ownerId, MediaPurpose purpose) {
            return new MediaUpload(prefix, ownerId, purpose, null, false);
        }

        public static MediaUpload tracking(UUID bidId, UUID ownerId) {
            return new MediaUpload("tracking/" + bidId + "/", ownerId, MediaPurpose.TRACKING, bidId, false);
        }
    }

    /**
     * Seule porte d'entrée des photos (hors chat) : quotas, validation, ré-encodage
     * (1600 px / JPEG 80, avatar 512 px), écriture R2 puis inscription au registre.
     * Les quotas sont vérifiés AVANT tout travail : un compte qui boucle ne coûte rien.
     */
    public String storeImage(MultipartFile file, MediaUpload upload) {
        validatePrefix(upload.prefix());
        validateFile(file);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_FILE_TYPE", "Invalid File Type", "Cannot read file content");
        }
        String key = upload.prefix() + Instant.now().toEpochMilli() + "_" + UUID.randomUUID() + ".jpg";
        MediaUploadEntity reservation = reserve(key, upload);
        try {
            byte[] stored = upload.purpose() == MediaPurpose.AVATAR
                    ? imageProcessingService.resizeForStorage(bytes,
                            ImageProcessingService.AVATAR_LONG_EDGE, ImageProcessingService.AVATAR_QUALITY)
                    : imageProcessingService.resizeForStorage(bytes,
                            ImageProcessingService.STORAGE_LONG_EDGE, ImageProcessingService.STORAGE_QUALITY);
            putBytes(key, stored, "image/jpeg");
            finalizeSize(reservation, stored.length);
            log.info("storeImage: key={} purpose={} {} -> {} octets", key, upload.purpose(), bytes.length,
                    stored.length);
            return key;
        } catch (RuntimeException e) {
            release(reservation);
            throw e;
        }
    }

    /**
     * Réserve la ligne du registre AVANT l'écriture R2, sous un verrou par propriétaire (et par
     * bid pour le tracking) : le comptage et l'insertion deviennent atomiques, des envois
     * simultanés ne dépassent donc pas le quota (le back tourne sur une seule instance).
     * Fail-closed : si l'écriture de la réservation échoue, l'envoi échoue.
     * Les envois exemptés (admin) ne sont pas comptés mais sont inscrits quand même.
     */
    private MediaUploadEntity reserve(String key, MediaUpload upload) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("storeImage: réservation de quota dans une transaction englobante : "
                    + "le quota peut être contourné (key={})", key);
        }
        MediaUploadEntity row = new MediaUploadEntity(key, upload.ownerId(), upload.purpose(), upload.bidId(), 0L);
        if (upload.quotaExempt()) {
            mediaUploadRepository.saveAndFlush(row);
            return row;
        }
        int ownerStripe = stripeOf(upload.ownerId());
        int bidStripe = upload.purpose() == MediaPurpose.TRACKING && upload.bidId() != null
                ? stripeOf(upload.bidId()) : ownerStripe;
        // Ordre croissant des verrous : pas d'interblocage entre deux envois.
        int first = Math.min(ownerStripe, bidStripe);
        int second = Math.max(ownerStripe, bidStripe);
        synchronized (QUOTA_LOCKS[first]) {
            if (second == first) {
                checkQuotasAndSave(row, upload);
            } else {
                synchronized (QUOTA_LOCKS[second]) {
                    checkQuotasAndSave(row, upload);
                }
            }
        }
        return row;
    }

    private void checkQuotasAndSave(MediaUploadEntity row, MediaUpload upload) {
        enforceQuotas(upload);
        mediaUploadRepository.saveAndFlush(row);
    }

    private static int stripeOf(UUID id) {
        return Math.floorMod(id.hashCode(), QUOTA_LOCKS.length);
    }

    /**
     * Quota journalier par utilisateur (hors tracking) et plafond des photos de suivi par bid,
     * tous deux sur 24 h glissantes. Le tracking est exempté du quota journalier : une étape de
     * suivi (départ, transit, livraison) ne doit jamais être bloquée par les autres envois du
     * voyageur, et un ancien client qui renvoie la photo à chaque essai retrouve son plafond
     * le lendemain au lieu d'être bloqué à vie sur ce colis.
     */
    private void enforceQuotas(MediaUpload upload) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        boolean tracking = upload.purpose() == MediaPurpose.TRACKING;
        if (!tracking && mediaUploadRepository.countByOwnerIdAndPurposeNotAndCreatedAtAfter(
                upload.ownerId(), MediaPurpose.TRACKING, since) >= settings.photoUploadDailyQuota()) {
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "photo-upload-quota-exceeded", "Too Many Requests",
                    "Vous avez atteint la limite de photos pour aujourd'hui. Réessayez demain.");
        }
        if (tracking && upload.bidId() != null
                && mediaUploadRepository.countByBidIdAndPurposeAndCreatedAtAfter(
                        upload.bidId(), MediaPurpose.TRACKING, since) >= settings.trackingPhotosMaxPerBid()) {
            throw new YadonyBusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "tracking-photo-limit-reached", "Too Many Requests",
                    "Ce colis a déjà le nombre maximum de photos de suivi.");
        }
    }

    /** Met à jour la taille réelle ; la ligne est déjà comptée, un échec ici est seulement journalisé. */
    private void finalizeSize(MediaUploadEntity reservation, long size) {
        try {
            reservation.setSizeBytes(size);
            mediaUploadRepository.save(reservation);
        } catch (RuntimeException e) {
            log.warn("storeImage: taille non enregistrée pour {} : {}", reservation.getObjectKey(), e.toString());
        }
    }

    /**
     * Annule une réservation dont l'envoi a échoué. {@code media_uploads} est un registre technique
     * de quotas, pas une entité métier : la ligne annulée est supprimée physiquement (exception
     * assumée à la règle du soft delete).
     */
    private void release(MediaUploadEntity reservation) {
        try {
            mediaUploadRepository.delete(reservation);
        } catch (RuntimeException e) {
            log.warn("storeImage: réservation non annulée pour {} : {}", reservation.getObjectKey(), e.toString());
        }
    }

    /** Clés d'une photo de messagerie : image redimensionnée et miniature carrée. */
    public record StoredImage(String mainKey, String thumbnailKey) {}

    /**
     * Valide une photo envoyée (non vide, 5 Mo, JPEG/PNG/WebP, octets d'en-tête cohérents
     * avec le type déclaré) sans la stocker. Mêmes règles et mêmes erreurs que
     * {@link #storeImage}.
     */
    public void validateImageUpload(MultipartFile file) {
        validateFile(file);
    }

    /**
     * Photo de messagerie (FLUTTER-B4) : bord long ≤ 1280 px, miniature 400×400, JPEG 80 %, EXIF supprimées par le
     * ré-encodage, stockée sous {@code {prefix}{baseName}_full.jpg} et
     * {@code {prefix}{baseName}_thumb.jpg}.
     */
    public StoredImage storeMessagingImage(String prefix, String baseName, byte[] bytes, String contentType,
                                           UUID ownerId) {
        validatePrefix(prefix);
        String mainKey  = prefix + baseName + "_full.jpg";
        String thumbKey = prefix + baseName + "_thumb.jpg";
        MediaUploadEntity reservation = reserve(mainKey, MediaUpload.of(prefix, ownerId, MediaPurpose.MESSAGING));
        try {
            ImageProcessingService.ProcessedImage processed = imageProcessingService.process(bytes, contentType);
            putBytes(mainKey, processed.main(), "image/jpeg");
            try {
                putBytes(thumbKey, processed.thumbnail(), "image/jpeg");
            } catch (RuntimeException e) {
                deleteQuietly(mainKey);
                throw e;
            }
            finalizeSize(reservation, processed.main().length + processed.thumbnail().length);
            return new StoredImage(mainKey, thumbKey);
        } catch (RuntimeException e) {
            release(reservation);
            throw e;
        }
    }

    /**
     * Octets d'un objet, vide s'il n'existe pas (purgé entre-temps). Toute autre erreur
     * remonte : une panne R2 ne doit pas passer pour une photo expirée.
     */
    public java.util.Optional<byte[]> downloadBytes(String objectKey) {
        try {
            return java.util.Optional.of(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build()).asByteArray());
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return java.util.Optional.empty();
        }
    }

    /** Suppression sans exception (nettoyage après un échec), l'erreur est seulement journalisée. */
    public void deleteQuietly(String objectKey) {
        try {
            deleteFile(objectKey);
        } catch (RuntimeException e) {
            log.warn("deleteQuietly FAILED key={}: {}", objectKey, e.toString());
        }
    }

    // -----------------------------------------------------------------------
    // Private S3 helpers
    // -----------------------------------------------------------------------

    private void putBytes(String key, byte[] data, String contentType) {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength((long) data.length)
                .build();

        long t0 = System.currentTimeMillis();
        try {
            s3Client.putObject(req, RequestBody.fromBytes(data));
            log.info("putBytes OK key={} bucket={} ({} ms)", key, bucket, System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            log.error("putBytes FAILED key={} bucket={} ({} ms): {}", key, bucket,
                    System.currentTimeMillis() - t0, ex.toString(), ex);
            throw ex;
        }
    }

    /**
     * Generate a presigned URL for temporary read access (default: 1 hour).
     */
    public String generatePresignedUrl(String objectKey, Duration expiry) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build())
                .build();

        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Copie server-side un objet S3 existant vers une NOUVELLE clé sous {@code destPrefix}
     * (extension préservée). Utilisé pour donner au bid sa propre copie des photos de la
     * demande (lifecycle/cleanup indépendants). Renvoie la nouvelle clé.
     */
    public String copyObject(String sourceKey, String destPrefix) {
        String ext = sourceKey.contains(".") ? sourceKey.substring(sourceKey.lastIndexOf('.')) : ".jpg";
        String destKey = destPrefix + Instant.now().toEpochMilli() + "_" + UUID.randomUUID() + ext;
        long t0 = System.currentTimeMillis();
        try {
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(sourceKey)
                    .destinationBucket(bucket).destinationKey(destKey)
                    .build());
            log.info("copyObject OK src={} dest={} ({} ms)", sourceKey, destKey, System.currentTimeMillis() - t0);
            return destKey;
        } catch (Exception ex) {
            log.error("copyObject FAILED src={} dest={} ({} ms): {}", sourceKey, destKey,
                    System.currentTimeMillis() - t0, ex.toString(), ex);
            throw ex;
        }
    }

    /**
     * Delete a file from S3 (RGPD deletion).
     */
    public void deleteFile(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build());
    }

    /**
     * Bulk delete all objects under a given prefix (e.g., "kyc/userId/").
     * Used for GDPR account deletion. No-op if prefix is empty.
     */
    public void deleteByPrefix(String prefix) {
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix);
            if (continuationToken != null) reqBuilder.continuationToken(continuationToken);
            ListObjectsV2Response list = s3Client.listObjectsV2(reqBuilder.build());
            if (!list.contents().isEmpty()) {
                List<ObjectIdentifier> identifiers = list.contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .toList();
                DeleteObjectsResponse resp = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(identifiers).build())
                        .build());
                if (!resp.errors().isEmpty()) {
                    log.error("S3 deleteObjects partial failure: {} objects not deleted under prefix '{}'",
                            resp.errors().size(), prefix);
                }
            }
            continuationToken = list.isTruncated() ? list.nextContinuationToken() : null;
        } while (continuationToken != null);
    }

    /**
     * Cles d'un prefixe dont la date de derniere modification precede le seuil.
     * Utilise pour la purge des pieces jointes orphelines du support.
     */
    public List<String> listKeysOlderThan(String prefix, Instant cutoff) {
        validatePrefix(prefix);
        String continuationToken = null;
        List<String> result = new java.util.ArrayList<>();
        do {
            ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix);
            if (continuationToken != null) reqBuilder.continuationToken(continuationToken);
            ListObjectsV2Response list = s3Client.listObjectsV2(reqBuilder.build());
            list.contents().stream()
                    .filter(o -> o.lastModified().isBefore(cutoff))
                    .map(software.amazon.awssdk.services.s3.model.S3Object::key)
                    .forEach(result::add);
            continuationToken = list.isTruncated() ? list.nextContinuationToken() : null;
        } while (continuationToken != null);
        return result;
    }

    /** Au plus {@code max} clés du préfixe modifiées avant {@code cutoff} (rapprochement). */
    public List<String> listObjectsOlderThan(String prefix, Instant cutoff, int max) {
        validatePrefix(prefix);
        List<String> result = new java.util.ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
            if (token != null) req.continuationToken(token);
            ListObjectsV2Response page = s3Client.listObjectsV2(req.build());
            for (var o : page.contents()) {
                if (o.lastModified().isBefore(cutoff)) {
                    result.add(o.key());
                    if (result.size() >= max) return result;
                }
            }
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        return result;
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                    "FILE_EMPTY", "Empty File", "File is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "FILE_TOO_LARGE", "File Too Large", "File exceeds 5MB limit");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(file.getContentType())) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_FILE_TYPE", "Invalid File Type", "Only JPEG, PNG and WebP images are allowed");
        }
        validateFileMagicBytes(file);
    }

    // Validates actual file content against expected magic bytes to prevent
    // MIME-type spoofing (e.g. client declaring image/jpeg but uploading a script).
    private void validateFileMagicBytes(MultipartFile file) {
        byte[] header = new byte[MAGIC_HEADER_SIZE];
        int bytesRead;
        try (InputStream is = file.getInputStream()) {
            bytesRead = is.read(header, 0, MAGIC_HEADER_SIZE);
        } catch (IOException e) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_FILE_TYPE", "Invalid File Type", "Cannot read file content");
        }
        if (bytesRead < 3) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_FILE_TYPE", "Invalid File Type", "File content is too small to validate");
        }

        String contentType = file.getContentType();
        if ("image/webp".equals(contentType)) {
            validateWebpMagicBytes(header, bytesRead);
            return;
        }

        byte[] expected = FILE_MAGIC_BYTES.get(contentType);
        if (expected == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_FILE_TYPE", "Invalid File Type", "Unsupported file type");
        }
        for (int i = 0; i < expected.length; i++) {
            if (header[i] != expected[i]) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_FILE_TYPE", "Invalid File Type", "File content does not match declared type");
            }
        }
    }

    private void validateWebpMagicBytes(byte[] header, int bytesRead) {
        if (bytesRead < 12) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_FILE_TYPE", "Invalid File Type", "File content does not match declared type");
        }
        // bytes 0-3: "RIFF"
        byte[] riff = {'R', 'I', 'F', 'F'};
        for (int i = 0; i < 4; i++) {
            if (header[i] != riff[i]) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_FILE_TYPE", "Invalid File Type", "File content does not match declared type");
            }
        }
        // bytes 8-11: "WEBP"
        byte[] webp = {'W', 'E', 'B', 'P'};
        for (int i = 0; i < 4; i++) {
            if (header[8 + i] != webp[i]) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_FILE_TYPE", "Invalid File Type", "File content does not match declared type");
            }
        }
    }

    private void validatePrefix(String prefix) {
        boolean valid = ALLOWED_PREFIXES.stream().anyMatch(prefix::startsWith);
        if (!valid) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                    "INVALID_PREFIX", "Invalid Prefix", "Invalid upload destination");
        }
    }
}
