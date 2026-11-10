package com.yadony.api.notifications;

import java.util.Map;
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
 *
 * <p>Tient aussi le <b>type effectif</b> d'une notification ({@link #effectiveType}) : le type
 * selon lequel le serveur applique ses règles (réglages, heures calmes, plafonds, e-mail),
 * quand il diffère du type porté par le push et le feed.
 */
public final class NotificationTypes {

    private NotificationTypes() {}

    public static final Set<String> CRITICAL = Set.of(
            "PAYMENT_RELEASED", "DELIVERY_CONFIRMED", "DISPUTE_OPENED",
            "HANDOVER_REMINDER_H2", "TRIP_RESCHEDULED"
    );

    public static boolean isCritical(String type) {
        return type != null && CRITICAL.contains(type);
    }

    /**
     * Relance automatique de la vérification d'identité. Jamais émis tel quel : le push et le
     * feed gardent {@code KYC_ACTION_REQUIRED}, que l'app sait ouvrir sur l'écran de
     * vérification, avec le drapeau {@code reminder=true}. Seules les règles du serveur suivent
     * ce type : réglable par « Rappels et conseils », compté dans les plafonds du jour, e-mail
     * avec désinscription en un clic.
     */
    public static final String KYC_VERIFICATION_REMINDER = "KYC_VERIFICATION_REMINDER";

    static final String KYC_ACTION_REQUIRED = "KYC_ACTION_REQUIRED";
    static final String REMINDER_FLAG = "reminder";

    /**
     * Type selon lequel appliquer les règles d'envoi. {@code KYC_ACTION_REQUIRED} avec
     * {@code reminder=true} est une relance ({@link #KYC_VERIFICATION_REMINDER}) ; sans le
     * drapeau, c'est une décision et le type reste inchangé, comme tout autre type.
     */
    public static String effectiveType(String type, Map<String, String> data) {
        if (KYC_ACTION_REQUIRED.equals(type) && data != null && "true".equals(data.get(REMINDER_FLAG))) {
            return KYC_VERIFICATION_REMINDER;
        }
        return type;
    }
}
