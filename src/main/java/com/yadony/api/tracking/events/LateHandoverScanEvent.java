package com.yadony.api.tracking.events;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un scan de remise (DEPART) hors ligne est arrivé APRÈS que le colis a été tranché comme non remis
 * (absence de l'expéditeur confirmée, remise manquée), alors que son heure hors ligne est antérieure
 * à cette décision : le colis a sans doute été remis et voyage, mais l'argent a déjà suivi la
 * décision (séquestre rendu à l'expéditeur). Le scan reste refusé (422 {@code bid-not-accepted}) :
 * c'est à l'équipe de trancher. Publié dans la transaction du scan, qui est annulée par ce refus ;
 * l'écouteur ({@code admin/LateHandoverScanAlertListener}) écrit donc l'alerte et l'audit dans des
 * transactions indépendantes.
 *
 * @param bidStatus        statut du colis au moment du rejeu ({@code CANCELLED} ou {@code NO_SHOW})
 * @param rejectionReason  motif de la décision ({@code SENDER_NO_SHOW}, {@code HANDOVER_NOT_CONFIRMED}, …)
 * @param decidedAt        heure de la décision (UTC)
 * @param offlineTimestamp heure du scan hors ligne (UTC), antérieure à {@code decidedAt}
 * @param photoKey         clé de la photo de remise, validée comme au chemin normal (ou nulle sans photo)
 * @param proofValidated   vrai seulement si une preuve a été vérifiée par le back : numéro de suivi
 *                         saisi et identique à celui du colis. Un scan QR ne transmet pas le contenu
 *                         du QR : il reste non vérifié. Dans tous les cas, l'heure hors ligne est
 *                         DÉCLARÉE PAR LE CLIENT et n'est jamais un fait établi.
 * @param proofMethod      {@code TRACKING_NUMBER} si le numéro a été vérifié, sinon {@code NONE}
 */
public record LateHandoverScanEvent(
        UUID bidId,
        UUID travelerId,
        String bidStatus,
        String rejectionReason,
        LocalDateTime decidedAt,
        LocalDateTime offlineTimestamp,
        String scanMethod,
        String photoKey,
        boolean proofValidated,
        String proofMethod
) {
}
