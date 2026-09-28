package com.yadony.api.kyc.events;

import java.util.UUID;

/**
 * Un administrateur a retire la verification d'identite d'un compte. Distinct de
 * {@link UserKycActionRequiredEvent} : le message a l'utilisateur n'est pas le meme (son
 * identite etait verifiee), meme si l'action attendue, refaire la verification, l'est.
 *
 * <p>Publie dans la transaction de la decision : le gel des versements du voyageur
 * ({@code payments/hold}) y est ecrit par un ecouteur synchrone.
 *
 * @param reasonCode code du catalogue ferme ({@code KycRejectionCodes}), jamais le motif
 *                   interne de l'administrateur
 * @param adminId    administrateur auteur de la revocation, acteur de l'audit du gel ;
 *                   {@code null} si inconnu
 */
public record UserKycRevokedEvent(UUID userId, String reasonCode, UUID adminId) {

    public UserKycRevokedEvent(UUID userId, String reasonCode) {
        this(userId, reasonCode, null);
    }
}
