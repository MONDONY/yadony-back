package com.yadony.api.admin.dto;

/**
 * Apercu : combien de comptes recevraient ce broadcast, sans rien envoyer.
 *
 * <p>Ciblage {@code USER} seulement : {@code targetUserName} (nom affichable du compte vise)
 * et {@code targetUserReachable} (faux pour un compte banni, en attente de suppression ou
 * supprime, auquel cas {@code recipientCount} vaut 0). Tous deux {@code null} sinon.
 */
public record BroadcastAudienceResponse(long recipientCount, String targetUserName, Boolean targetUserReachable) {

    public BroadcastAudienceResponse(long recipientCount) {
        this(recipientCount, null, null);
    }
}
