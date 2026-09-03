package com.yadony.api.notifications.dto;

import com.yadony.api.notifications.NotificationAggregate;
import com.yadony.api.notifications.NotificationEntity;
import com.yadony.api.notifications.NotificationText;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Une ligne du feed. Soit une notification seule ({@code count} = 1), soit
 * l'agrégat d'un groupe non lu ({@code count} ≥ {@link NotificationAggregate#MIN_COUNT}),
 * auquel cas {@code id}, {@code body} et {@code data} sont ceux de la plus
 * récente, {@code title} et {@code deeplink} ceux du groupe, et
 * {@code notificationIds} liste tout ce que la ligne recouvre. Lire un
 * agrégat passe par {@code PATCH /notifications/groups/read}.
 */
public record FeedItemDTO(
        UUID id,
        String type,
        String category,
        String title,
        String body,
        String deeplink,
        String groupKey,
        Map<String, String> data,
        boolean read,
        LocalDateTime createdAt,
        int count,
        List<UUID> notificationIds
) {
    public static FeedItemDTO single(NotificationEntity e) {
        return new FeedItemDTO(
                e.getId(), e.getType(), e.getCategory().code(), e.getTitle(), e.getBody(),
                e.getDeeplink(), e.getGroupKey(), e.getData(), e.isRead(), e.getCreatedAt(),
                1, List.of(e.getId()));
    }

    /** {@code unreadNewestFirst} : les non-lues du groupe, la plus récente en tête. */
    public static FeedItemDTO aggregate(String groupKey, List<NotificationEntity> unreadNewestFirst) {
        NotificationEntity latest = unreadNewestFirst.get(0);
        NotificationText text = NotificationAggregate.text(groupKey, unreadNewestFirst.size(), latest);
        return new FeedItemDTO(
                latest.getId(), latest.getType(), latest.getCategory().code(), text.title(), text.body(),
                NotificationAggregate.deeplink(groupKey, latest).orElse(null), groupKey, latest.getData(),
                false, latest.getCreatedAt(),
                unreadNewestFirst.size(), unreadNewestFirst.stream().map(NotificationEntity::getId).toList());
    }
}
