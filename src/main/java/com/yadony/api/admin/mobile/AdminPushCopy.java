package com.yadony.api.admin.mobile;

import com.yadony.api.admin.notifications.AdminNotificationType;

/**
 * Texte des push admin. Générique par type : jamais de nom, de montant ni d'extrait de message,
 * un écran verrouillé ne doit rien révéler. Le détail se lit dans l'application, après connexion.
 */
final class AdminPushCopy {

    static final String BODY = "Ouvrez l'application pour le détail.";

    private AdminPushCopy() {}

    static String titleFor(AdminNotificationType type) {
        return switch (type) {
            case REPORT_CREATED -> "Nouveau signalement";
            case SUPPORT_TICKET_CREATED -> "Nouveau ticket support";
            case SUPPORT_MESSAGE_RECEIVED -> "Nouveau message support";
            case DISPUTE_OPENED -> "Nouveau litige";
            case NOSHOW_PENDING -> "Absence à examiner";
            case KYC_IN_REVIEW -> "Vérification d'identité à examiner";
            case PAYOUT_HELD -> "Versement retenu";
            case WALLET_REFUND_REQUESTED -> "Remboursement demandé";
            case GDPR_REQUESTED -> "Demande RGPD";
            case ADMIN_ALERT -> "Nouvelle alerte";
        };
    }
}
