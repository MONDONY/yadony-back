package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.PageResponse;
import com.yadony.api.notifications.dto.NotificationDTO;
import com.yadony.api.notifications.dto.NotificationDetailDTO;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final NotificationCapsPolicy capsPolicy;

    public NotificationService(NotificationRepository repository, UserRepository userRepository,
                               NotificationCapsPolicy capsPolicy) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.capsPolicy = capsPolicy;
    }

    /**
     * Seul point de persistance d'une notification : c'est ici que le contrat de
     * forme s'applique à tous les points d'émission d'un coup. Catégorie, clé de
     * groupe et deeplink sont dérivés par l'entité ; les caps sont contrôlés par
     * {@link NotificationCapsPolicy}. Une annonce plateforme est la seule dont le
     * corps est raccourci : son texte complet est gardé dans {@code fullBody},
     * parce qu'il n'existe nulle part ailleurs dans l'app.
     */
    public NotificationEntity persist(UUID userId, String type, String title, String body,
                                      Map<String, String> data, boolean isCritical) {
        var entity = new NotificationEntity(userId, type, title, body, data, isCritical);
        if (entity.getCategory() == NotificationCategory.ANNONCE) {
            entity.summarize(NotificationCaps.truncateAtWord(body, NotificationCaps.BODY_MAX), body);
        }
        capsPolicy.check(type, entity.getTitle(), entity.getBody());
        return repository.save(entity);
    }

    public NotificationEntity persist(UUID userId, String type, String title, String body,
                                      Map<String, String> data) {
        return persist(userId, type, title, body, data, false);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDTO> list(String firebaseUid, int page, int size) {
        UUID userId = resolveUserId(firebaseUid);
        return PageResponse.from(
                repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                          .map(NotificationDTO::from));
    }

    @Transactional(readOnly = true)
    public NotificationDetailDTO detail(String firebaseUid, UUID notificationId) {
        return NotificationDetailDTO.from(requireOwned(firebaseUid, notificationId));
    }

    @Transactional(readOnly = true)
    public long countUnread(String firebaseUid) {
        return repository.countByUserIdAndReadAtIsNull(resolveUserId(firebaseUid));
    }

    public void markRead(String firebaseUid, UUID notificationId) {
        var entity = requireOwned(firebaseUid, notificationId);
        if (!entity.isRead()) {
            entity.markRead(LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    public int markAllRead(String firebaseUid) {
        return repository.markAllReadByUserId(resolveUserId(firebaseUid), LocalDateTime.now(ZoneOffset.UTC));
    }

    public void softDelete(String firebaseUid, UUID notificationId) {
        requireOwned(firebaseUid, notificationId)
                .setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    // Story 8.3 — Flutter sends ACK to prevent SMS fallback
    public void ack(String firebaseUid, UUID notificationId) {
        var entity = requireOwned(firebaseUid, notificationId);
        if (entity.getAckedAt() == null) {
            entity.markAcked(LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    private NotificationEntity requireOwned(String firebaseUid, UUID notificationId) {
        UUID userId = resolveUserId(firebaseUid);
        var entity = repository.findById(notificationId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "not-found", "Not found", "Notification introuvable"));
        if (!entity.getUserId().equals(userId)) {
            throw new YadonyBusinessException(
                    HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Accès refusé");
        }
        return entity;
    }

    private UUID resolveUserId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(UserEntity::getId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));
    }
}
