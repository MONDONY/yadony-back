package com.yadony.api.admin.notifications;

import java.time.Instant;

/**
 * Une entrée du fil. {@code id} vaut {@code <TYPE>:<entityId>} : stable d'un appel à l'autre,
 * le front s'en sert comme clé. {@code title} et {@code summary} ne portent jamais de coordonnée
 * (téléphone, email) : un prénom et une initiale au plus.
 */
public record AdminNotificationItem(
        String id,
        AdminNotificationType type,
        String title,
        String summary,
        AdminNotificationSeverity severity,
        Instant createdAt,
        String link
) {}
