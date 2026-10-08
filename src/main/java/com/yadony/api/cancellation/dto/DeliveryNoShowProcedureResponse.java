package com.yadony.api.cancellation.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * État de la procédure « destinataire absent » d'un colis (FLUTTER-E2), vu par l'expéditeur ou
 * le voyageur. Sert l'écran de signalement du voyageur (compteur d'attente, preuve de contact)
 * et le suivi de la garde des deux côtés (nouveau RDV, colis non réclamé).
 *
 * @param role                 {@code SENDER} ou {@code TRAVELER} : le rôle de l'appelant.
 * @param bidStatus            statut courant du colis.
 * @param arrivedAt            arrivée déclarée (UTC), null si inconnue (colis arrivé avant V301).
 * @param reportAvailableAt    heure à partir de laquelle le voyageur peut signaler ; null tant
 *                             que l'arrivée n'est pas déclarée.
 * @param waitElapsed          le délai d'attente minimal est écoulé.
 * @param contactProof         preuve de contact trouvée ({@code CALL}, {@code MESSAGE}) ou null.
 * @param canReport            le voyageur peut signaler maintenant (toutes conditions réunies,
 *                             hors case de confirmation qu'il coche au signalement).
 * @param reported             un signalement « destinataire absent » existe pour ce colis.
 * @param noShowStatus         statut du signalement (PENDING_CONFIRMATION, CONTESTED, CONFIRMED,
 *                             RESOLVED) ou null.
 * @param contestationDeadline fin du délai de contestation de l'expéditeur.
 * @param holdUntil            fin de la garde du colis par le voyageur ; null sans procédure.
 * @param retryAppointmentAt   nouveau rendez-vous fixé par l'expéditeur, ou null.
 * @param retryAppointmentNote précisions de l'expéditeur sur ce rendez-vous.
 * @param unclaimedAt          le colis est passé « non réclamé » à cette date, ou null.
 * @param canSetRetryAppointment l'expéditeur peut (re)fixer un nouveau rendez-vous.
 * @param minWaitMinutes       délai d'attente minimal configuré.
 * @param holdDays             durée de garde configurée.
 */
public record DeliveryNoShowProcedureResponse(
        UUID bidId,
        String role,
        String bidStatus,
        OffsetDateTime arrivedAt,
        OffsetDateTime reportAvailableAt,
        boolean waitElapsed,
        String contactProof,
        boolean canReport,
        boolean reported,
        String noShowStatus,
        OffsetDateTime contestationDeadline,
        OffsetDateTime holdUntil,
        OffsetDateTime retryAppointmentAt,
        String retryAppointmentNote,
        OffsetDateTime unclaimedAt,
        boolean canSetRetryAppointment,
        int minWaitMinutes,
        int holdDays
) {}
