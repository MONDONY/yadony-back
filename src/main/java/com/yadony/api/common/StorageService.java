package com.yadony.api.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class StorageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageService.class);

    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "tracking/", "users/", "messaging/", "kyc/", "package_requests/", "requests/", "bids/", "reports/",
            "support/");
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
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

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final ImageProcessingService imageProcessingService;

    @Value("${aws.s3.bucket}")
    private String bucket;

    public StorageService(S3Client s3Client, S3Presigner s3Presigner,
                          ImageProcessingService imageProcessingService) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.imageProcessingService = imageProcessingService;
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
     * Upload a file to S3 under the given prefix.
     * Allowed prefixes: "tracking/" and "users/"
     *
     * @return the S3 object key
     */
    public String uploadFile(MultipartFile file, String prefix) throws IOException {
        log.info("uploadFile: prefix={}, originalFilename={}, contentType={}, size={} bytes",
            prefix, file.getOriginalFilename(), file.getContentType(), file.getSize());
        validateFile(file);
        validatePrefix(prefix);

        String key = prefix + Instant.now().toEpochMilli() + "_" + UUID.randomUUID() + getExtension(file);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();

        long t0 = System.currentTimeMillis();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
            log.info("uploadFile: putObject OK key={} bucket={} ({} ms)",
                key, bucket, System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            log.error("uploadFile: putObject FAILED key={} bucket={} ({} ms): {}",
                key, bucket, System.currentTimeMillis() - t0, ex.toString(), ex);
            throw ex;
        }

        return key;
    }

    /** Keys returned after uploading a request photo (main + thumbnail). */
    public record UploadedRequestPhoto(String mainKey, String thumbnailKey) {}

    /**
     * Upload a sender's request photo with automatic resize + thumbnail.
     * <ul>
     *   <li>Main image: resized so long-edge ≤ 1280 px, JPEG 80 %, stored under
     *       {@code requests/{senderId}/{ts}_request.jpg}</li>
     *   <li>Thumbnail: 400×400 center-crop, JPEG 80 %, stored under
     *       {@code requests/{senderId}/{ts}_request_thumb.jpg}</li>
     * </ul>
     * EXIF metadata is stripped as a side-effect of re-encoding.
     *
     * @param senderId    UUID of the sender who owns the photo
     * @param bytes       raw bytes of the original upload
     * @param contentType MIME type of the original file
     * @return {@link UploadedRequestPhoto} containing both S3 keys
     */
    public UploadedRequestPhoto uploadRequestPhoto(UUID senderId, byte[] bytes, String contentType) {
        ImageProcessingService.ProcessedImage processed = imageProcessingService.process(bytes, contentType);

        long ts = Instant.now().toEpochMilli();
        String mainKey  = "requests/" + senderId + "/" + ts + "_request.jpg";
        String thumbKey = "requests/" + senderId + "/" + ts + "_request_thumb.jpg";

        putBytes(mainKey,  processed.main(),      "image/jpeg");
        putBytes(thumbKey, processed.thumbnail(), "image/jpeg");

        return new UploadedRequestPhoto(mainKey, thumbKey);
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

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                    "FILE_EMPTY", "Empty File", "File is empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "FILE_TOO_LARGE", "File Too Large", "File exceeds 10MB limit");
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

    private String getExtension(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) return ".jpg";
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
