package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * La modération vient de retirer une demande d'envoi. Écouté par {@code notifications/}
 * pour prévenir l'expéditeur dans sa langue.
 *
 * <p>Ne porte QUE le code du motif public catalogué ({@code AnnouncementRemovalReason}) :
 * la note interne du modérateur reste dans {@code audit_log} et ne doit jamais atteindre
 * l'expéditeur (elle peut nommer le signalant).
 */
public record PackageRequestRemovedByAdminEvent(
        UUID packageRequestId,
        UUID senderId,
        String publicReasonCode
) {
}
