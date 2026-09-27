package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * Une demande d'envoi vient d'être signalée pour la première fois par ce signalant (le
 * signalement idempotent n'en publie pas de second). Publié dans la transaction du
 * signalement : {@code signalements/} en tire, dans la même transaction, la ligne de la
 * boîte générique des signalements. {@code reason} est le motif brut envoyé par l'app.
 */
public record PackageRequestReportedEvent(
        UUID packageRequestId,
        UUID reporterId,
        String reason,
        String details
) {
}
