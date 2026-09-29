package com.yadony.api.support;

import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.support.dto.SupportAttachmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pieces jointes du support. L'upload est volontairement detache du ticket :
 * c'est ce qui permet de joindre une image au tout premier message, quand le
 * ticket n'existe pas encore.
 */
@Service
public class SupportAttachmentService {

    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 4;

    private static final Duration PRESIGNED_TTL = Duration.ofHours(1);

    private final StorageService storageService;
    private final SupportMessageAttachmentRepository attachmentRepository;

    public SupportAttachmentService(StorageService storageService,
                                    SupportMessageAttachmentRepository attachmentRepository) {
        this.storageService = storageService;
        this.attachmentRepository = attachmentRepository;
    }

    public String userPrefix(UUID userId) {
        return "support/" + userId + "/";
    }

    public String adminPrefix(UUID adminId) {
        return "support/admin/" + adminId + "/";
    }

    /** Le prefixe est construit a partir de l'identifiant, jamais d'une entree client. */
    public String uploadForUser(UUID userId, MultipartFile file) throws IOException {
        return storageService.uploadFile(file, userPrefix(userId));
    }

    public String uploadForAdmin(UUID adminId, MultipartFile file) throws IOException {
        return storageService.uploadFile(file, adminPrefix(adminId));
    }

    /**
     * Copie cote serveur (R2 CopyObject) d'objets deja stockes vers le prefixe support
     * de l'utilisateur ; renvoie les nouvelles cles, dans l'ordre. Sert aux captures
     * d'un signalement reprises dans la conversation ouverte pour y repondre : le fil
     * a ses propres objets (independants du cycle de vie de reports/), et ils tombent
     * sous {@code support/{userId}/}, que la finalisation de compte purge (RGPD).
     *
     * <p>Les cles sources ne viennent JAMAIS du client : l'appelant les lit en base
     * (photos d'un signalement), ce qui dispense du controle de prefixe.
     */
    public List<String> copyIntoUserPrefix(UUID userId, List<String> sourceKeys) {
        if (sourceKeys == null || sourceKeys.isEmpty()) {
            return List.of();
        }
        String prefix = userPrefix(userId);
        return sourceKeys.stream().map(key -> storageService.copyObject(key, prefix)).toList();
    }

    /**
     * Garde-fou central : sans lui, un message pourrait referencer la cle d'un
     * fichier appartenant a quelqu'un d'autre.
     */
    public List<String> requireOwnedKeys(List<String> keys, String expectedPrefix) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        if (keys.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "support-too-many-attachments", "Trop de pieces jointes",
                    "Un message accepte au maximum " + MAX_ATTACHMENTS_PER_MESSAGE + " images.");
        }
        for (String key : keys) {
            if (key == null || !key.startsWith(expectedPrefix)) {
                throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "support-attachment-not-owned", "Piece jointe invalide",
                        "Une piece jointe ne vous appartient pas.");
            }
        }
        return keys;
    }

    @Transactional
    public void attach(UUID messageId, List<String> keys, String contentType) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<SupportMessageAttachmentEntity> entities = new java.util.ArrayList<>();
        for (String key : keys) {
            SupportMessageAttachmentEntity entity = new SupportMessageAttachmentEntity();
            entity.setMessageId(messageId);
            entity.setObjectKey(key);
            entity.setContentType(contentType);
            entity.setSizeBytes(0L);
            entities.add(entity);
        }
        attachmentRepository.saveAll(entities);
    }

    public Map<UUID, List<SupportAttachmentResponse>> responsesFor(Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        return attachmentRepository.findByMessageIdInOrderByCreatedAtAsc(messageIds).stream()
                .collect(Collectors.groupingBy(
                        SupportMessageAttachmentEntity::getMessageId,
                        Collectors.mapping(this::toResponse, Collectors.toList())));
    }

    private SupportAttachmentResponse toResponse(SupportMessageAttachmentEntity entity) {
        return new SupportAttachmentResponse(
                entity.getId(),
                storageService.generatePresignedUrl(entity.getObjectKey(), PRESIGNED_TTL),
                entity.getContentType(),
                entity.getSizeBytes());
    }
}
