package com.yadony.api.common;

import java.util.UUID;

/**
 * Résout l'identifiant saisi par {@code callerUserId} en compte destinataire. Lance une
 * {@link YadonyBusinessException} : 404 {@code user-not-found} (inconnu, supprimé, suspendu, bloqué ou format
 * invalide), 422 {@code recipient-invitation-self} (soi-même), 429 {@code recipient-lookup-quota} (plafond horaire).
 * Interface portée par {@code common/} pour que les autres packages n'injectent pas le service d'annuaire.
 */
public interface RecipientAccountResolver {

    RecipientAccount resolve(UUID callerUserId, String rawUsername);
}
