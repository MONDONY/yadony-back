package com.yadony.api.gppro;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.media.MediaPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Téléversement et purge des justificatifs d'une demande GP Pro. */
@Service
public class GpProDocumentService {

    private static final Logger log = LoggerFactory.getLogger(GpProDocumentService.class);

    /** Documents non rattachés qu'un utilisateur peut avoir en attente. */
    static final int MAX_UNATTACHED_DOCUMENTS = 4;

    private static final String PREFIX = "gp_pro/";

    private final GpProDocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;

    public GpProDocumentService(GpProDocumentRepository documentRepository,
                                UserRepository userRepository,
                                StorageService storageService) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.storageService = storageService;
    }

    /** Images seules (JPEG, PNG, WebP) : la validation est celle de {@code storeImage}. */
    @Transactional
    public GpProDocumentEntity upload(String firebaseUid, MultipartFile file, GpProDocumentType type) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));
        // Verrou par propriétaire : sérialise comptage et insertion (limite de 4 non contournable en parallèle).
        userRepository.findByIdForUpdate(user.getId());
        if (documentRepository.countByOwnerIdAndApplicationIdIsNullAndPurgedAtIsNull(user.getId())
                >= MAX_UNATTACHED_DOCUMENTS) {
            throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "gp-pro-too-many-documents", "problem.gp-pro-too-many-documents.unattached", MAX_UNATTACHED_DOCUMENTS);
        }
        String key = storageService.storeImage(file,
                StorageService.MediaUpload.of(PREFIX + user.getId() + "/", user.getId(), MediaPurpose.GP_PRO));
        GpProDocumentEntity doc = new GpProDocumentEntity();
        doc.setOwnerId(user.getId());
        doc.setDocType(type);
        doc.setStorageKey(key);
        return documentRepository.save(doc);
    }

    /** Supprime le fichier puis efface la clé. Rend {@code false} (clé gardée) si le stockage échoue. */
    @Transactional
    public boolean purge(GpProDocumentEntity doc) {
        String key = doc.getStorageKey();
        if (key == null) {
            return false;
        }
        try {
            storageService.deleteFile(key);
        } catch (RuntimeException e) {
            log.warn("Purge du justificatif GP Pro {} impossible, clé conservée", doc.getId(), e);
            return false;
        }
        doc.setStorageKey(null);
        doc.setPurgedAt(LocalDateTime.now(ZoneOffset.UTC));
        documentRepository.save(doc);
        return true;
    }

    /** Purge tous les justificatifs non purgés d'une demande ; rend le nombre purgés. */
    @Transactional
    public int purgeAll(UUID applicationId) {
        int purged = 0;
        for (GpProDocumentEntity doc : documentRepository.findByApplicationIdAndPurgedAtIsNull(applicationId)) {
            if (purge(doc)) {
                purged++;
            }
        }
        return purged;
    }
}
