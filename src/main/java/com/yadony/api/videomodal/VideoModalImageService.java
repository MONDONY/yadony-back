package com.yadony.api.videomodal;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.media.MediaPurpose;
import com.yadony.api.videomodal.dto.VideoModalAdminResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Envoi et retrait de l'image d'un modal INFO depuis l'admin.
 *
 * <p>Fichier : JPEG, PNG ou WebP reconnu par ses octets de tête (pas seulement le type annoncé),
 * 3 Mo au plus, 2400 px au plus de côté. Stockage {@code video-modals/{campagne}/{horodatage}.{ext}}
 * via {@link StorageService} (jamais le chemin KYC). Volontairement sans transaction englobante :
 * l'écriture R2 ne doit pas tenir une connexion, chaque appel de dépôt a sa propre transaction.
 */
@Service
public class VideoModalImageService {

    static final long MAX_BYTES = 3L * 1024 * 1024;
    static final int MAX_SIDE = 2400;

    private final VideoModalCampaignRepository campaignRepository;
    private final VideoModalAdminService adminService;
    private final StorageService storageService;
    private final AuditService auditService;

    public VideoModalImageService(VideoModalCampaignRepository campaignRepository,
                                  VideoModalAdminService adminService,
                                  StorageService storageService,
                                  AuditService auditService) {
        this.campaignRepository = campaignRepository;
        this.adminService = adminService;
        this.storageService = storageService;
        this.auditService = auditService;
    }

    /** Remplace l'image de la campagne ; l'ancienne est supprimée du stockage. */
    public VideoModalAdminResponse upload(UUID id, MultipartFile file, UUID adminId) {
        VideoModalCampaignEntity c = campaignRepository.findById(id).orElseThrow(() -> VideoModalService.notFound(id));
        if (c.getType() != VideoModalType.INFO) {
            throw invalid("video-modal-image-info-only", "Seuls les modals d'information peuvent avoir une image");
        }
        // Vide (400), type annoncé hors JPEG/PNG/WebP ou octets de tête incohérents (422).
        storageService.validateImageUpload(file);
        if (file.getSize() > MAX_BYTES) {
            throw invalid("video-modal-image-too-heavy", "L'image ne peut pas dépasser 3 Mo");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw invalid("video-modal-image-unreadable", "Impossible de lire le fichier envoyé");
        }
        if (bytes.length > MAX_BYTES) {
            throw invalid("video-modal-image-too-heavy", "L'image ne peut pas dépasser 3 Mo");
        }
        checkDimensions(bytes);

        String contentType = normalizedContentType(file.getContentType());
        String key = storageService.storeAsset(bytes, contentType, extensionOf(contentType),
                // Envoi d'un admin : hors quota ; propriétaire = l'admin (la campagne si inconnu).
                new StorageService.MediaUpload(VideoModalImageReferenceSource.PREFIX + id + "/",
                        adminId != null ? adminId : id, MediaPurpose.VIDEO_MODAL, null, true));
        String previous = c.getImageKey();
        c.setImageKey(key);
        try {
            campaignRepository.save(c);
        } catch (RuntimeException e) {
            storageService.deleteQuietly(key);
            throw e;
        }
        if (previous != null) storageService.deleteQuietly(previous);

        Map<String, Object> payload = basePayload(c);
        payload.put("imageKey", key);
        payload.put("imageType", contentType);
        payload.put("sizeBytes", bytes.length);
        auditService.log(VideoModalAdminService.AUDIT_ENTITY, id, "VIDEO_MODAL_IMAGE_UPLOADED", adminId, payload);
        return adminService.get(id);
    }

    /** Retire l'image ; le mode d'image repasse à NONE (il exige une image). Sans image : sans effet. */
    public VideoModalAdminResponse remove(UUID id, UUID adminId) {
        VideoModalCampaignEntity c = campaignRepository.findById(id).orElseThrow(() -> VideoModalService.notFound(id));
        String previous = c.getImageKey();
        if (previous == null) return adminService.get(id);
        VideoModalImageMode previousMode = c.getImageMode();
        c.setImageKey(null);
        c.setImageMode(VideoModalImageMode.NONE);
        campaignRepository.save(c);
        storageService.deleteQuietly(previous);

        Map<String, Object> payload = basePayload(c);
        payload.put("imageKey", previous);
        payload.put("previousImageMode", previousMode.name());
        auditService.log(VideoModalAdminService.AUDIT_ENTITY, id, "VIDEO_MODAL_IMAGE_REMOVED", adminId, payload);
        return adminService.get(id);
    }

    private static Map<String, Object> basePayload(VideoModalCampaignEntity c) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", c.getType().name());
        payload.put("title", c.getTitleFr());
        payload.put("version", c.getVersion());
        return payload;
    }

    /** Lit largeur et hauteur dans l'en-tête, sans décoder l'image. */
    private static void checkDimensions(byte[] bytes) {
        int width;
        int height;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw invalid("video-modal-image-invalid", "Le fichier n'est pas une image lisible");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (YadonyBusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid("video-modal-image-invalid", "Le fichier n'est pas une image lisible");
        }
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw invalid("video-modal-image-too-large", "L'image ne peut pas dépasser " + MAX_SIDE
                    + " px de côté (reçue : " + width + " x " + height + ")");
        }
    }

    private static String normalizedContentType(String declared) {
        String type = declared == null ? "" : declared.toLowerCase(Locale.ROOT);
        return "image/jpg".equals(type) ? "image/jpeg" : type;
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static YadonyBusinessException invalid(String code, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Validation Error", detail);
    }
}
