package com.yadony.api.admin.notifications;

import java.time.Instant;
import java.util.List;

/**
 * Réponse de {@code GET /admin/notifications/feed}.
 *
 * @param unreadCount  entrées de la fenêtre plus récentes que {@code lastSeenAt}, plafonné à 99
 * @param unreadCapped vrai quand le vrai nombre dépasse le plafond (le front affiche « 99+ »)
 * @param lastSeenAt   seuil effectif du non-lu : la dernière consultation, ou il y a 7 jours
 *                     pour un administrateur qui n'a jamais ouvert la cloche
 * @param readIds      entrées de cette page que l'administrateur a déjà lues une à une
 *                     ({@code POST /admin/notifications/{id}/read}) ; absent sur un back plus ancien
 */
public record AdminNotificationFeedResponse(
        List<AdminNotificationItem> items,
        int unreadCount,
        boolean unreadCapped,
        Instant lastSeenAt,
        List<String> readIds
) {}
