package com.yadony.api.notifications.dto;

import com.yadony.api.notifications.NotificationEntity;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Une notification seule, avec son texte complet. Sert l'écran de détail
 * générique, celui qu'ouvre une ligne sans {@code deeplink} : une annonce
 * plateforme n'existe nulle part ailleurs dans l'app, donc c'est ici que son
 * texte vit. Pour toute autre notification {@code fullBody} est nul, et
 * l'écran cible porte déjà le contenu.
 */
public record NotificationDetailDTO(
        UUID id,
        String type,
        String category,
        String title,
        String body,
        String fullBody,
        String deeplink,
        String groupKey,
        Map<String, String> data,
        boolean read,
        LocalDateTime createdAt
) {
    public static NotificationDetailDTO from(NotificationEntity e) {
        return new NotificationDetailDTO(
                e.getId(),
                e.getType(),
                e.getCategory().code(),
                e.getTitle(),
                e.getBody(),
                e.getFullBody(),
                e.getDeeplink(),
                e.getGroupKey(),
                e.getData(),
                e.isRead(),
                e.getCreatedAt()
        );
    }
}
