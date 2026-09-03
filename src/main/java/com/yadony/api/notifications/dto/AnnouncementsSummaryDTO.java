package com.yadony.api.notifications.dto;

import com.yadony.api.notifications.NotificationEntity;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * La carte « Annonces yadony » en tête du sheet : son compteur de non-lus et
 * la dernière annonce reçue. Sans aucune annonce, seuls le compteur (0) et
 * des champs nuls sont servis.
 */
public record AnnouncementsSummaryDTO(
        long unreadCount,
        UUID latestId,
        String latestTitle,
        LocalDateTime latestAt
) {
    public static AnnouncementsSummaryDTO of(long unreadCount, Optional<NotificationEntity> latest) {
        return new AnnouncementsSummaryDTO(
                unreadCount,
                latest.map(NotificationEntity::getId).orElse(null),
                latest.map(NotificationEntity::getTitle).orElse(null),
                latest.map(NotificationEntity::getCreatedAt).orElse(null));
    }
}
