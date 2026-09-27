package com.yadony.api.kyc.events;

import java.util.UUID;

/**
 * Un administrateur a retire la verification d'identite d'un compte. Distinct de
 * {@link UserKycActionRequiredEvent} : le message a l'utilisateur n'est pas le meme (son
 * identite etait verifiee), meme si l'action attendue, refaire la verification, l'est.
 *
 * @param reasonCode code du catalogue ferme ({@code KycRejectionCodes}), jamais le motif
 *                   interne de l'administrateur
 */
public record UserKycRevokedEvent(UUID userId, String reasonCode) {
}
