package com.yadony.api.admin.notifications;

import java.util.Map;

/**
 * Réponse de {@code GET /admin/notifications/counters}. {@code counts} compte les éléments À
 * TRAITER par entrée de menu ; une clé est absente quand l'administrateur n'a pas la permission
 * de l'entrée (jamais un zéro trompeur).
 */
public record AdminNotificationCountersResponse(
        Map<String, Long> counts,
        int unreadCount,
        boolean unreadCapped
) {}
