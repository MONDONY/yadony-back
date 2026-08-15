package com.yadony.api.notifications;

import java.util.Set;

/**
 * Types de notification critiques, au sens « l'utilisateur doit être joint ».
 *
 * <p>Ces trois-là ignorent les préférences de push ({@code NotificationPrefsService})
 * et déclenchent un SMS de repli si aucun ACK n'arrive dans les 60 s
 * ({@code SmsFallbackScheduler}). Leur push porte aussi {@code content-available}
 * côté APNS, sans quoi iOS ne réveillerait pas l'application et l'ACK ne
 * pourrait jamais partir tant que l'utilisateur n'ouvre pas la notification —
 * le SMS partirait alors systématiquement.
 *
 * <p>Le client Flutter tient la même liste dans {@code notification_service.dart} :
 * toute modification ici doit y être reportée, sinon un type devient critique
 * côté serveur sans être accusé côté client.
 */
public final class NotificationTypes {

    private NotificationTypes() {}

    public static final Set<String> CRITICAL = Set.of(
            "PAYMENT_RELEASED", "DELIVERY_CONFIRMED", "DISPUTE_OPENED",
            "HANDOVER_REMINDER_H2"
    );

    public static boolean isCritical(String type) {
        return type != null && CRITICAL.contains(type);
    }
}
